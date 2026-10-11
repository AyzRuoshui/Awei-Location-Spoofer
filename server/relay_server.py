#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
双机远程摄像头 · 中转服务器（Relay Server）
协议说明：参考 awei-spoofer common 模块中 Proto.kt 的帧协议规范

职责：按「远程 ID」路由的 TCP 透明管道。只解析信令帧，透传段不解析——
配对码与 H.264 画面数据对服务器完全不可见。

信令（JSON，帧格式 [len:4 大端][type:1][payload]，type 0x01=JSON）：
  远程端 -> reg{devid,ver}   -> reg_ok{id} / reg_fail{reason}
  主端   -> dial{id,device}  -> dial_ok{name} / dial_fail{reason: badid|offline|busy}
  拨通：先向远程端发 incoming{app}（该连接转入透传），再向主端回 dial_ok（转入透传）
  会话结束：服务器关闭两条连接；远程端约 5s 自动重连重注册（devid->id 映射持久化，ID 稳定）
  心跳：服务器每 30s 向控制态连接发 ping，客户端回 pong；控制态 45s 无消息判死

实现要点：
- 控制态读帧 = select([数据socket, 唤醒socket]) + 缓冲区组帧（半包不丢）。
  拨通瞬间（另一线程置 pass 后写唤醒管道），阻塞中的读帧确定性地立即跳出
  转透传——不存在"最后一段控制帧/第一批透传字节被误解析或丢弃"的竞态窗口。
- 会话结束语义（与两端客户端对齐）：任一侧断开即关闭两条连接并摘除注册，
  远程端客户端自动重连拿回同一 ID。

部署说明：
  python3 relay_server.py 监听 0.0.0.0:${AWEL_PORT:-17520}
  设备映射持久化：同目录下 devices.json
  可通过环境变量自定义监听端口，例如：
    AWEL_PORT=17520 python3 relay_server.py
"""

import json
import os
import random
import select
import socket
import struct
import threading
import time

HOST = "0.0.0.0"
PORT = int(os.environ.get("AWEL_PORT", "17520"))
MAX_CTRL_FRAME = 1 * 1024 * 1024   # 信令帧上限（透传态不解析帧，不受此限）
MAX_CONNS = 256                    # 并发连接上限（防滥用）
PING_INTERVAL = 30                 # 服务器心跳周期（秒）
CTRL_READ_TIMEOUT = 45             # 控制态空闲判死：必须大于心跳周期
POLL = 0.5                         # select 空闲轮询周期（秒），只为空闲判死服务
DEV_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "devices.json")

_loglock = threading.Lock()


def log(msg):
    with _loglock:
        print("%s %s" % (time.strftime("%Y-%m-%d %H:%M:%S"), msg), flush=True)


def close_quiet(*objs):
    for o in objs:
        if o is None:
            continue
        try:
            o.close()
        except Exception:
            pass


def kill_sock(s):
    """跨线程安全地关数据 socket：CPython 里另一线程阻塞在 recv/select 上时
    直接 close() 不会唤醒它（阻塞 syscall 持有文件描述符引用，FIN 也发不出），
    必须 shutdown() 让阻塞立即返回，再 close()。"""
    if s is None:
        return
    try:
        s.shutdown(socket.SHUT_RDWR)
    except Exception:
        pass
    close_quiet(s)


# ---------------- 设备映射持久化（devid -> rid，保证远程 ID 稳定） ----------------

devmap = {}
devmap_lock = threading.Lock()


def load_devmap():
    global devmap
    try:
        with open(DEV_FILE, "r", encoding="utf-8") as f:
            devmap = {str(k): str(v) for k, v in json.load(f).items()}
    except Exception:
        devmap = {}
    log("设备映射载入 %d 条" % len(devmap))


def save_devmap():
    tmp = DEV_FILE + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(devmap, f)
    os.replace(tmp, DEV_FILE)


# ---------------- 连接对象与注册表 ----------------

remotes = {}          # rid -> Conn（控制态在线远程端）
remotes_lock = threading.Lock()
conn_seq = [0]
conn_seq_lock = threading.Lock()


class Conn(object):
    def __init__(self, sock, addr):
        with conn_seq_lock:
            conn_seq[0] += 1
            self.no = conn_seq[0]
        self.sock = sock
        self.addr = addr
        self.role = "?"        # remote / master
        self.rid = None
        self.devid = None
        self.state = "ctrl"    # ctrl / pass
        self.peer = None       # 透传对端 Conn
        self.wlock = threading.Lock()  # 控制态写锁（心跳与应答互斥；状态切换以此为 barrier）
        self.alive = True
        # 自唤醒管道：读帧线程 select 在 [数据socket, wake_r] 上，
        # 其他线程置 pass 后写 wake_w 一字节，读帧线程立即跳出转透传
        self.wake_r, self.wake_w = socket.socketpair()


def wake(conn):
    try:
        conn.wake_w.sendall(b"\x01")
    except Exception:
        pass


def end_session(conn, reason=""):
    """幂等拆除：关掉本连接与其透传对端，摘除远程注册。"""
    with remotes_lock:
        if not conn.alive:
            return
        conn.alive = False
        peer = conn.peer
        conn.peer = None
        if conn.role == "remote" and conn.rid and remotes.get(conn.rid) is conn:
            del remotes[conn.rid]
        kill_sock(conn.sock)
        close_quiet(conn.wake_r, conn.wake_w)
        if peer is not None and peer.alive:
            peer.alive = False
            peer.peer = None
            if peer.role == "remote" and peer.rid and remotes.get(peer.rid) is peer:
                del remotes[peer.rid]
            kill_sock(peer.sock)
            close_quiet(peer.wake_r, peer.wake_w)
    if reason:
        log("会话拆除 #%d: %s" % (conn.no, reason))


# ---------------- 帧读取（控制态） ----------------

class _Transition(Exception):
    """控制态被拨通打断，应立即转入透传泵"""


class FrameReader(object):
    """select(数据socket + 唤醒socket) + 缓冲区组帧：半包不丢；被唤醒后
    下一次循环迭代立即检查 abort()，状态切换零延迟、无字节竞态窗口。"""

    def __init__(self, conn):
        self.conn = conn
        self.buf = b""
        self.last_rx = time.time()

    def read_frame(self):
        """返回 (type, payload)；流结束返回 None；应转透传抛 _Transition；
        控制态空闲超过 CTRL_READ_TIMEOUT 抛 TimeoutError（判死）。"""
        conn = self.conn
        while True:
            if not conn.alive or conn.state != "ctrl":
                raise _Transition()
            if len(self.buf) >= 4:
                (n,) = struct.unpack(">I", self.buf[:4])
                if n < 1 or n > MAX_CTRL_FRAME:
                    raise ValueError("bad frame len %d" % n)
                if len(self.buf) >= 4 + n:
                    body = self.buf[4:4 + n]
                    self.buf = self.buf[4 + n:]
                    return body[0], body[1:]
            r, _, _ = select.select([conn.sock, conn.wake_r], [], [], POLL)
            if not r:
                if not conn.alive or conn.state != "ctrl":
                    raise _Transition()
                if time.time() - self.last_rx > CTRL_READ_TIMEOUT:
                    raise TimeoutError("control idle >%ds" % CTRL_READ_TIMEOUT)
                continue
            if conn.wake_r in r:
                # 状态切换唤醒： draining 唤醒字节后回循环顶部重查状态
                try:
                    conn.wake_r.recv(4096)
                except Exception:
                    pass
                continue
            chunk = conn.sock.recv(65536)
            if not chunk:
                return None
            self.last_rx = time.time()
            self.buf += chunk


def send_json(sock, obj):
    data = json.dumps(obj, separators=(",", ":")).encode("utf-8")
    sock.sendall(struct.pack(">IB", 1 + len(data), 0x01) + data)


# ---------------- 信令处理 ----------------

def on_reg(conn, j):
    devid = str(j.get("devid", "")).strip()[:64]
    if not devid:
        send_json(conn.sock, {"t": "reg_fail", "reason": "baddevid"})
        return
    with remotes_lock:
        with devmap_lock:
            rid = devmap.get(devid)
            if rid is None:
                live = set(remotes.keys()) | set(devmap.values())
                while True:
                    rid = str(random.randint(100000, 999999))
                    if rid not in live:
                        break
                devmap[devid] = rid
                save_devmap()
        old = remotes.get(rid)
        if old is not None and old is not conn:
            # 顶掉旧链路（半死连接重连的典型场景）
            old.alive = False
            old.peer = None
            kill_sock(old.sock)
            close_quiet(old.wake_r, old.wake_w)
            log("顶掉旧连接 #%d rid=%s" % (old.no, rid))
        conn.role = "remote"
        conn.rid = rid
        conn.devid = devid
        remotes[rid] = conn
    send_json(conn.sock, {"t": "reg_ok", "id": rid})
    log("注册 #%d rid=%s devid=%s... from=%s" % (conn.no, rid, devid[:8], conn.addr))


def fail(conn, t, reason):
    try:
        send_json(conn.sock, {"t": t, "reason": reason})
    except Exception:
        pass


def on_dial(conn, j):
    rid = str(j.get("id", "")).strip()[:16]
    device = str(j.get("device", ""))[:64] or "unknown"
    with remotes_lock:
        remote = remotes.get(rid)
        if remote is None:
            fail(conn, "dial_fail", "offline" if rid in devmap.values() else "badid")
            log("拨号失败 rid=%s（不存在/不在线）from=%s" % (rid, conn.addr))
            return
        if remote.state != "ctrl" or not remote.alive:
            fail(conn, "dial_fail", "busy")
            return
        # 关键时序：先让远程端进透传（发 incoming + 唤醒其读帧线程），
        # 再回主端 dial_ok——主端收到 dial_ok 才开始发 hello，保证远程端
        # 读帧线程已让位给透传泵，无字节错序窗口
        try:
            with remote.wlock:
                send_json(remote.sock, {"t": "incoming", "app": device})
                remote.state = "pass"
                remote.peer = conn
                wake(remote)
        except Exception:
            remotes.pop(rid, None)
            remote.alive = False
            kill_sock(remote.sock)
            close_quiet(remote.wake_r, remote.wake_w)
            fail(conn, "dial_fail", "offline")
            return
        conn.role = "master"
        try:
            with conn.wlock:
                conn.state = "pass"
                conn.peer = remote
                send_json(conn.sock, {"t": "dial_ok", "name": ""})
        except Exception:
            fail(conn, "dial_fail", "offline")
            return
    log("拨通 #%d<->#%d rid=%s app=%s" % (conn.no, remote.no, rid, device))


def pinger(conn):
    """控制态每 30s 一跳；转入透传后立即退出（绝不往媒体流里写心跳）。"""
    while True:
        time.sleep(PING_INTERVAL)
        with conn.wlock:
            if not conn.alive or conn.state != "ctrl":
                return
            try:
                send_json(conn.sock, {"t": "ping"})
            except Exception:
                kill_sock(conn.sock)
                return


def pump(conn):
    """透传泵：本连接收到的原始字节原样转发给对端。任一侧结束即双向拆除。"""
    peer = conn.peer
    try:
        while True:
            data = conn.sock.recv(65536)
            if not data:
                break
            peer.sock.sendall(data)
    except Exception:
        pass
    finally:
        end_session(conn, "透传结束")


def handle(sock, addr):
    conn = Conn(sock, addr)
    threading.Thread(target=pinger, args=(conn,), daemon=True).start()
    reader = FrameReader(conn)
    try:
        frame = reader.read_frame()
        if frame is None:
            raise ValueError("空连接")
        if frame[0] != 0x01:
            raise ValueError("首帧非 JSON")
        j = json.loads(frame[1].decode("utf-8"))
        t = j.get("t")
        if t == "reg":
            on_reg(conn, j)
        elif t == "dial":
            on_dial(conn, j)
        else:
            raise ValueError("首帧非 reg/dial: %s" % t)

        while conn.alive and conn.state == "ctrl":
            f = reader.read_frame()
            if f is None:
                break
            if f[0] != 0x01:
                continue
            j2 = json.loads(f[1].decode("utf-8"))
            t2 = j2.get("t")
            if t2 == "ping":
                with conn.wlock:
                    if conn.alive and conn.state == "ctrl":
                        send_json(sock, {"t": "pong"})
            elif t2 == "reg" and conn.role == "remote":
                on_reg(conn, j2)  # 幂等重注册
            elif t2 == "detach":
                # 客户端 v1 会话后走重连，正常到不了这里；礼貌回应保持兼容
                with conn.wlock:
                    send_json(sock, {"t": "reg_ok", "id": conn.rid or ""})
            # 其余忽略
    except _Transition:
        pass
    except TimeoutError as e:
        log("控制态判死 #%d %s: %s" % (conn.no, addr, e))
    except Exception as e:
        log("连接 #%d %s 异常: %s" % (conn.no, addr, e))
    finally:
        if conn.state == "pass" and conn.alive and conn.peer is not None:
            pump(conn)
        end_session(conn, "连接关闭")
        log("断开 #%d %s role=%s rid=%s" % (conn.no, addr, conn.role, conn.rid))


def main():
    load_devmap()
    ss = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    ss.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    ss.bind((HOST, PORT))
    ss.listen(64)
    log("Relay Server 监听 %s:%d" % (HOST, PORT))
    active = [0]

    while True:
        try:
            sock, addr = ss.accept()
        except Exception as e:
            log("accept 异常: %s" % e)
            time.sleep(0.5)
            continue
        if active[0] >= MAX_CONNS:
            close_quiet(sock)
            continue
        active[0] += 1

        def guarded(s=sock, a=addr):
            try:
                handle(s, a)
            finally:
                active[0] -= 1

        threading.Thread(target=guarded, daemon=True).start()


if __name__ == "__main__":
    main()

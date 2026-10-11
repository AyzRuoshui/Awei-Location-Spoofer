# Awei RemoteCam Relay Server (独立自建中转服务器)

本目录提供了一个独立、轻量级的 TCP 中转服务器程序 `relay_server.py`，用于实现两台 Android 手机在跨网络、无公网 IP 直连（如均为移动 4G/5G 数据蜂窝网络）环境下的远程摄像头画面与信令透传。

---

## 核心设计

- **纯标准库**：仅基于 Python 3 标准库（`socket`, `select`, `struct`, `threading`, `json`），零外部 pip 依赖，极低内存开销（单连接 < 100KB）。
- **端到端加密安全**：服务器仅在握手阶段解析 JSON 控制信令以完成配对路由；进入传输态后，服务器作为纯粹的 TCP 二进制管道透传 H.264 视频流与画面，服务器端不解析亦不留存任何视频内容。
- **设备 ID 持久化**：副机（采集端）首次连接时，服务器根据其硬件持久标识 `devid` 自动分配一个唯一的 6 位数字远程 ID，并持久化到本地 `devices.json`。副机断线重连时仍能分配相同的 6 位远程 ID，无需频繁重新输入。

---

## 快速启动

### 1. 环境准备
- Python 3.8 或更高版本；
- 一台具有公网 IP 的云服务器（Linux / Windows / macOS 均可），或者局域网内的一台主机。

### 2. 直接前台运行
```bash
# 默认监听 0.0.0.0:17520
python3 relay_server.py

# 或通过环境变量自定义监听端口（例如 8888）
AWEL_PORT=8888 python3 relay_server.py
```

### 3. 防火墙与安全组配置
请确保您的云服务器防火墙以及云服务商安全组（Security Group）中放行了对应的 TCP 端口（默认 TCP 17520）。

---

## Linux systemd 常驻服务配置 (推荐)

若在 Linux 服务器上长期运行，推荐配置 systemd 守护进程：

1. 将 `relay_server.py` 放置于 `/opt/relay/relay_server.py`；
2. 创建服务文件 `/etc/systemd/system/awei-relay.service`：
   ```ini
   [Unit]
   Description=Awei RemoteCam Relay Server
   After=network.target

   [Service]
   Type=simple
   User=root
   WorkingDirectory=/opt/relay
   Environment=AWEL_PORT=17520
   ExecStart=/usr/bin/python3 /opt/relay/relay_server.py
   Restart=always
   RestartSec=3

   [Install]
   WantedBy=multi-user.target
   ```
3. 启动并设置开机自启：
   ```bash
   systemctl daemon-reload
   systemctl enable awei-relay
   systemctl start awei-relay
   systemctl status awei-relay
   ```
4. 查看服务日志：
   ```bash
   journalctl -u awei-relay -f
   ```

---

## 在 App 端配置与使用

1. **副机（远程摄像头采集端）**：
   - 打开 `AweiRemoteCam` 远程摄像头 App；
   - 在连接设置中启用「使用中转服务器」；
   - 填入您的自建中转服务器地址：`您的服务器公网IP:17520`；
   - 点击注册/连接，成功后主界面将显示该副机的 **6位数字远程 ID**（例如 `839201`）。

2. **主机（满血版伪装端）**：
   - 打开 `AweiSpoofer` 满血版；
   - 进入虚拟摄像头设置，源选择「双机远程摄像头」；
   - 勾选「经中转服务器连接」，在中转地址处填入 `您的服务器公网IP:17520`；
   - 填入副机显示的 6 位远程 ID；
   - 点击连接/拨号，即可在两台手机之间建立加密视频串流。

import paramiko
import time

def start_server_backend():
    host = "47.111.227.125"
    username = "root"
    password = "Cwp521.."

    try:
        ssh = paramiko.SSHClient()
        ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        ssh.connect(host, username=username, password=password, timeout=10)

        # 启动后端
        jar_path = "/opt/ai-meeting-room/target/ai-meeting-room-1.0.0-SNAPSHOT.jar"
        log_path = "/opt/ai-meeting-room/app.log"

        # 清理旧日志
        ssh.exec_command(f"echo '' > {log_path} 2>/dev/null")

        # 使用nohup在后台启动
        cmd = f"cd /opt/ai-meeting-room && nohup java -jar {jar_path} > {log_path} 2>&1 &"
        print(f"Starting backend: {cmd}")
        stdin, stdout, stderr = ssh.exec_command(cmd)
        time.sleep(1)  # 让进程启动

        # 检查进程
        check_cmd = "ps aux | grep 'ai-meeting-room' | grep -v grep | grep -v python"
        stdin, stdout, stderr = ssh.exec_command(check_cmd)
        processes = stdout.read().decode().strip()
        print(f"Processes: {processes}")

        # 检查端口
        time.sleep(2)
        port_cmd = "netstat -ln | grep ':8080' 2>/dev/null || ss -ln | grep ':8080' 2>/dev/null || echo 'Port 8080 not listening'"
        stdin, stdout, stderr = ssh.exec_command(port_cmd)
        port_status = stdout.read().decode().strip()
        print(f"Port 8080: {port_status}")

        # 检查日志前几行
        log_cmd = f"tail -20 {log_path} 2>/dev/null || echo 'Log file empty'"
        stdin, stdout, stderr = ssh.exec_command(log_cmd)
        log_output = stdout.read().decode().strip()
        print(f"\nLog output (last 20 lines):\n{log_output}")

        ssh.close()
        return True
    except Exception as e:
        print(f"SSH failed: {e}")
        return False

if __name__ == "__main__":
    start_server_backend()
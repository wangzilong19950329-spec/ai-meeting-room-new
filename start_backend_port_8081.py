import paramiko
import time

def start_backend_8081():
    host = "47.111.227.125"
    username = "root"
    password = "Cwp521.."

    try:
        ssh = paramiko.SSHClient()
        ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        ssh.connect(host, username=username, password=password, timeout=10)

        jar_path = "/opt/ai-meeting-room/target/ai-meeting-room-1.0.0-SNAPSHOT.jar"
        log_path = "/opt/ai-meeting-room/app_8081.log"

        # 杀死所有相关进程
        ssh.exec_command("pkill -f 'ai-meeting-room' 2>/dev/null")
        time.sleep(2)

        # 使用8081端口启动
        cmd = f"cd /opt/ai-meeting-room && nohup java -jar {jar_path} --server.port=8081 >> {log_path} 2>&1 &"
        print(f"Starting on port 8081: {cmd}")
        stdin, stdout, stderr = ssh.exec_command(cmd)
        time.sleep(5)

        # 检查进程
        check_cmd = "ps aux | grep 'ai-meeting-room' | grep -v grep | grep -v python"
        stdin, stdout, stderr = ssh.exec_command(check_cmd)
        processes = stdout.read().decode().strip()
        print(f"Processes: {processes}")

        # 检查端口
        port_cmd = "ss -tln | grep ':8081'"
        stdin, stdout, stderr = ssh.exec_command(port_cmd)
        port_status = stdout.read().decode().strip()
        print(f"Port 8081: {port_status}")

        # 检查日志
        log_cmd = f"tail -30 {log_path}"
        stdin, stdout, stderr = ssh.exec_command(log_cmd)
        logs = stdout.read().decode().strip()
        print(f"\nLogs:\n{logs}")

        # 检查启动完成
        started_cmd = f"grep -i 'started\\|running' {log_path} | tail -2"
        stdin, stdout, stderr = ssh.exec_command(started_cmd)
        started = stdout.read().decode().strip()
        print(f"\nStarted: {started}")

        # 检查错误
        error_cmd = f"grep -i 'error\\|exception\\|fail' {log_path} | grep -v 'Error starting ApplicationContext' | tail -5"
        stdin, stdout, stderr = ssh.exec_command(error_cmd)
        errors = stdout.read().decode().strip()
        if errors:
            print(f"\nErrors:\n{errors}")

        ssh.close()
        return True
    except Exception as e:
        print(f"SSH failed: {e}")
        return False

if __name__ == "__main__":
    start_backend_8081()
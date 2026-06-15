import paramiko
import time

def restart_backend():
    host = "47.111.227.125"
    username = "root"
    password = "Cwp521.."

    try:
        ssh = paramiko.SSHClient()
        ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        ssh.connect(host, username=username, password=password, timeout=10)

        jar_path = "/opt/ai-meeting-room/target/ai-meeting-room-1.0.0-SNAPSHOT.jar"
        log_path = "/opt/ai-meeting-room/app.log"

        # 清理日志
        ssh.exec_command(f"echo '=== Restarting backend ===' > {log_path}")

        # 启动
        cmd = f"cd /opt/ai-meeting-room && nohup java -jar {jar_path} >> {log_path} 2>&1 &"
        print(f"Starting: {cmd}")
        stdin, stdout, stderr = ssh.exec_command(cmd)
        time.sleep(3)

        # 检查进程
        check_cmd = "ps aux | grep 'ai-meeting-room' | grep -v grep | grep -v python | head -2"
        stdin, stdout, stderr = ssh.exec_command(check_cmd)
        processes = stdout.read().decode().strip()
        print(f"Processes: {processes}")

        # 等待启动
        time.sleep(5)

        # 检查日志中的启动状态
        log_cmd = f"tail -50 {log_path}"
        stdin, stdout, stderr = ssh.exec_command(log_cmd)
        logs = stdout.read().decode().strip()
        print(f"\nRecent logs:\n{logs}")

        # 检查端口
        port_cmd = "ss -tln | grep ':8080'"
        stdin, stdout, stderr = ssh.exec_command(port_cmd)
        port_status = stdout.read().decode().strip()
        print(f"\nPort 8080: {port_status}")

        # 检查是否有错误
        error_cmd = f"grep -i 'error\\|exception\\|fail' {log_path} | tail -5"
        stdin, stdout, stderr = ssh.exec_command(error_cmd)
        errors = stdout.read().decode().strip()
        if errors:
            print(f"\nErrors:\n{errors}")
        else:
            print("\nNo errors found in logs")

        # 检查启动完成消息
        started_cmd = f"grep -i 'started\\|running' {log_path} | tail -2"
        stdin, stdout, stderr = ssh.exec_command(started_cmd)
        started = stdout.read().decode().strip()
        print(f"\nStartup messages: {started}")

        ssh.close()
        return True
    except Exception as e:
        print(f"SSH failed: {e}")
        return False

if __name__ == "__main__":
    restart_backend()
import paramiko
import time

def check_logs():
    host = "47.111.227.125"
    username = "root"
    password = "Cwp521.."

    try:
        ssh = paramiko.SSHClient()
        ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        ssh.connect(host, username=username, password=password, timeout=10)

        # 等待几秒让后端完全启动
        time.sleep(5)

        # 获取最近日志
        cmd = "tail -30 /opt/ai-meeting-room/app.log"
        stdin, stdout, stderr = ssh.exec_command(cmd)
        logs = stdout.read().decode().strip()
        print("Recent logs:")
        print(logs)

        # 检查是否有错误
        error_cmd = "grep -i 'error\\|exception\\|fail' /opt/ai-meeting-room/app.log | tail -10"
        stdin, stdout, stderr = ssh.exec_command(error_cmd)
        errors = stdout.read().decode().strip()
        if errors:
            print("\nErrors found:")
            print(errors)

        # 检查数据库连接
        db_cmd = "grep -i 'datasource\\|mysql\\|jdbc' /opt/ai-meeting-room/app.log | tail -10"
        stdin, stdout, stderr = ssh.exec_command(db_cmd)
        db_logs = stdout.read().decode().strip()
        print("\nDatabase logs:")
        print(db_logs)

        ssh.close()
    except Exception as e:
        print(f"SSH failed: {e}")

if __name__ == "__main__":
    check_logs()
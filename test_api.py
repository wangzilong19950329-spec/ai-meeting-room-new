import paramiko
import time

def test_api():
    host = "47.111.227.125"
    username = "root"
    password = "Cwp521.."

    try:
        ssh = paramiko.SSHClient()
        ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        ssh.connect(host, username=username, password=password, timeout=10)

        # 从服务器本地测试API
        test_cmd = "curl -s -o /dev/null -w '%{http_code}' http://localhost:8081/api/meeting/list 2>&1 || echo 'curl failed'"
        stdin, stdout, stderr = ssh.exec_command(test_cmd)
        http_code = stdout.read().decode().strip()
        print(f"API test HTTP code: {http_code}")

        # 获取响应内容
        content_cmd = "curl -s http://localhost:8081/api/meeting/list 2>&1 | head -100"
        stdin, stdout, stderr = ssh.exec_command(content_cmd)
        content = stdout.read().decode().strip()
        print(f"API response (first 100 chars): {content[:100]}...")

        # 检查数据库连接日志
        log_cmd = "tail -20 /opt/ai-meeting-room/app_8081.log | grep -i 'hikari\\|mysql\\|connected\\|fail'"
        stdin, stdout, stderr = ssh.exec_command(log_cmd)
        db_logs = stdout.read().decode().strip()
        print(f"\nDatabase logs: {db_logs}")

        # 检查完整的启动后日志
        recent_cmd = "tail -30 /opt/ai-meeting-room/app_8081.log"
        stdin, stdout, stderr = ssh.exec_command(recent_cmd)
        recent = stdout.read().decode().strip()
        print(f"\nRecent logs:\n{recent}")

        ssh.close()
    except Exception as e:
        print(f"SSH failed: {e}")

if __name__ == "__main__":
    test_api()
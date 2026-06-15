import paramiko

def check_backend_8081():
    host = "47.111.227.125"
    username = "root"
    password = "Cwp521.."

    try:
        ssh = paramiko.SSHClient()
        ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        ssh.connect(host, username=username, password=password, timeout=10)

        print("=== Checking backend on port 8081 ===")

        # 1. 检查进程
        proc_cmd = "ps aux | grep 'ai-meeting-room' | grep -v grep | grep -v python"
        stdin, stdout, stderr = ssh.exec_command(proc_cmd)
        processes = stdout.read().decode().strip()
        print(f"Backend processes:\n{processes}")

        # 2. 检查端口
        port_cmd = "ss -tln | grep ':8081' 2>/dev/null || netstat -ln | grep ':8081' 2>/dev/null || echo 'Port 8081 not listening'"
        stdin, stdout, stderr = ssh.exec_command(port_cmd)
        port_status = stdout.read().decode().strip()
        print(f"\nPort 8081 status: {port_status}")

        # 3. 检查最新日志
        log_cmd = "tail -20 /opt/ai-meeting-room/app_8081.log 2>/dev/null || echo 'Log file not found'"
        stdin, stdout, stderr = ssh.exec_command(log_cmd)
        logs = stdout.read().decode().strip()
        print(f"\nRecent logs (last 20 lines):\n{logs}")

        # 4. 测试API端点
        api_cmd = "curl -s -o /dev/null -w '%{http_code}' http://localhost:8081/api/health 2>&1 || echo 'curl failed'"
        stdin, stdout, stderr = ssh.exec_command(api_cmd)
        http_code = stdout.read().decode().strip()
        print(f"\nAPI /health test: HTTP {http_code}")

        # 如果没有/health端点，测试其他
        if http_code == "000" or "failed" in http_code:
            api2_cmd = "curl -s -o /dev/null -w '%{http_code}' http://localhost:8081/api/auth/login -X POST -H 'Content-Type: application/json' -d '{\"username\":\"test\",\"password\":\"test\"}' 2>&1 | head -1"
            stdin, stdout, stderr = ssh.exec_command(api2_cmd)
            http_code2 = stdout.read().decode().strip()
            print(f"API /auth/login test: HTTP {http_code2}")

        ssh.close()

        # 判断后端状态
        if "LISTEN" in port_status or ":8081" in port_status:
            print("\n✅ Backend is running on port 8081")
            return True
        else:
            print("\n❌ Backend may not be running on port 8081")
            return False

    except Exception as e:
        print(f"SSH failed: {e}")
        return False

if __name__ == "__main__":
    check_backend_8081()
import paramiko

def check_mysql_status():
    host = "47.111.227.125"
    username = "root"
    password = "Cwp521.."

    try:
        ssh = paramiko.SSHClient()
        ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        ssh.connect(host, username=username, password=password, timeout=10)

        # 1. 检查MySQL服务状态
        print("=== MySQL Service Status ===")
        service_cmd = "systemctl status mysql 2>/dev/null || service mysql status 2>/dev/null || echo 'Cannot check service'"
        stdin, stdout, stderr = ssh.exec_command(service_cmd)
        print(stdout.read().decode().strip())

        # 2. 检查MySQL监听端口
        print("\n=== MySQL Listening Ports ===")
        port_cmd = "netstat -ln | grep ':3306' 2>/dev/null || ss -ln | grep ':3306' 2>/dev/null || echo 'Port 3306 not found'"
        stdin, stdout, stderr = ssh.exec_command(port_cmd)
        print(stdout.read().decode().strip())

        # 3. 检查MySQL绑定地址
        print("\n=== MySQL Bind Address ===")
        bind_cmd = "grep -r 'bind-address' /etc/mysql/ /etc/my.cnf /etc/my.cnf.d/ 2>/dev/null | head -5 || echo 'No bind-address config found'"
        stdin, stdout, stderr = ssh.exec_command(bind_cmd)
        print(stdout.read().decode().strip())

        # 4. 检查用户权限
        print("\n=== MySQL User Permissions ===")
        user_cmd = "mysql -u root -p'Cwp521..' -e \"SELECT user, host FROM mysql.user WHERE user='ai_meeting';\" 2>/dev/null || echo 'Cannot query users'"
        stdin, stdout, stderr = ssh.exec_command(user_cmd)
        print(stdout.read().decode().strip())

        # 5. 测试本地连接
        print("\n=== Test Local MySQL Connection ===")
        test_cmd = "mysql -u ai_meeting -p'Cwp521..' -h localhost -e 'SELECT 1;' 2>&1 | head -5"
        stdin, stdout, stderr = ssh.exec_command(test_cmd)
        local_test = stdout.read().decode().strip()
        print(f"Local connection test: {local_test}")

        # 6. 测试远程连接
        print("\n=== Test Remote MySQL Connection ===")
        remote_cmd = "mysql -u ai_meeting -p'Cwp521..' -h 47.111.227.125 -e 'SELECT 1;' 2>&1 | head -5"
        stdin, stdout, stderr = ssh.exec_command(remote_cmd)
        remote_test = stdout.read().decode().strip()
        print(f"Remote connection test: {remote_test}")

        # 7. 检查防火墙
        print("\n=== Firewall Rules for 3306 ===")
        firewall_cmd = "iptables -L INPUT -n --line-numbers | grep ':3306' 2>/dev/null || echo 'No firewall rule for 3306'"
        stdin, stdout, stderr = ssh.exec_command(firewall_cmd)
        print(stdout.read().decode().strip())

        # 8. 检查云服务商安全组（需要手动）
        print("\n=== Cloud Security Group ===")
        print("Note: Check Alibaba Cloud/AWS/GCP console for inbound rules on port 3306")

        ssh.close()
    except Exception as e:
        print(f"SSH failed: {e}")

if __name__ == "__main__":
    check_mysql_status()
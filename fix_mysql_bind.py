import paramiko

def fix_mysql_bind():
    host = "47.111.227.125"
    username = "root"
    password = "Cwp521.."

    try:
        ssh = paramiko.SSHClient()
        ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        ssh.connect(host, username=username, password=password, timeout=10)

        config_file = "/etc/my.cnf.d/mariadb-server.cnf"

        # 备份原配置
        print(f"Backing up {config_file}...")
        backup_cmd = f"cp {config_file} {config_file}.backup"
        stdin, stdout, stderr = ssh.exec_command(backup_cmd)

        # 查看当前内容
        print("\n=== Current bind-address setting ===")
        view_cmd = f"grep -n 'bind-address' {config_file} | head -5"
        stdin, stdout, stderr = ssh.exec_command(view_cmd)
        print(stdout.read().decode().strip())

        # 方案1：取消注释bind-address=0.0.0.0
        print("\n=== Uncommenting bind-address=0.0.0.0 ===")
        uncomment_cmd = f"sed -i 's/^#bind-address=0\\.0\\.0\\.0/bind-address=0.0.0.0/' {config_file}"
        stdin, stdout, stderr = ssh.exec_command(uncomment_cmd)
        print("Uncomment command executed")

        # 或者方案2：添加新的bind-address
        print("\n=== Adding bind-address=:: (IPv6) ===")
        add_ipv6_cmd = f"echo -e '\\n# Added for remote access\\nbind-address=::' >> {config_file}"
        stdin, stdout, stderr = ssh.exec_command(add_ipv6_cmd)
        print("IPv6 bind address added")

        # 查看修改后的内容
        print("\n=== Updated bind-address setting ===")
        stdin, stdout, stderr = ssh.exec_command(view_cmd)
        updated = stdout.read().decode().strip()
        print(updated)

        # 重启MySQL服务
        print("\n=== Restarting MySQL service ===")
        restart_cmd = "systemctl restart mariadb 2>/dev/null || systemctl restart mysql 2>/dev/null || service mysql restart"
        stdin, stdout, stderr = ssh.exec_command(restart_cmd)
        time.sleep(3)

        # 检查服务状态
        status_cmd = "systemctl status mariadb 2>/dev/null | head -5 || echo 'Cannot check status'"
        stdin, stdout, stderr = ssh.exec_command(status_cmd)
        print("Service status:", stdout.read().decode().strip())

        # 检查监听端口
        print("\n=== Checking listening ports after restart ===")
        port_cmd = "netstat -ln | grep ':3306' 2>/dev/null || ss -ln | grep ':3306' 2>/dev/null || echo 'Port 3306 not found'"
        stdin, stdout, stderr = ssh.exec_command(port_cmd)
        print(stdout.read().decode().strip())

        # 测试远程连接
        print("\n=== Testing remote connection ===")
        test_cmd = "mysql -u ai_meeting -p'Cwp521..' -h 47.111.227.125 -e 'SELECT 1;' 2>&1 | head -5"
        stdin, stdout, stderr = ssh.exec_command(test_cmd)
        test_result = stdout.read().decode().strip()
        print(f"Remote connection test: {test_result}")

        ssh.close()

        if "1" in test_result:
            print("\n✅ MySQL remote access fixed!")
            return True
        else:
            print("\n❌ MySQL remote access still failing")
            return False

    except Exception as e:
        print(f"SSH failed: {e}")
        return False

if __name__ == "__main__":
    import time
    fix_mysql_bind()
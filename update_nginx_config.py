import paramiko

def update_nginx_config():
    host = "47.111.227.125"
    username = "root"
    password = "Cwp521.."

    try:
        ssh = paramiko.SSHClient()
        ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        ssh.connect(host, username=username, password=password, timeout=10)

        nginx_config = "/etc/nginx/conf.d/ai-meeting.conf"

        # 1. 检查当前配置
        print("=== Current Nginx Config ===")
        check_cmd = f"cat {nginx_config} 2>/dev/null || echo 'Config file not found'"
        stdin, stdout, stderr = ssh.exec_command(check_cmd)
        current_config = stdout.read().decode().strip()
        print(current_config)

        # 2. 备份
        print(f"\n=== Backing up {nginx_config} ===")
        backup_cmd = f"cp {nginx_config} {nginx_config}.backup"
        stdin, stdout, stderr = ssh.exec_command(backup_cmd)
        print("Backup created")

        # 3. 更新端口8080 -> 8081
        print("\n=== Updating port 8080 to 8081 ===")
        update_cmd = f"sed -i 's/:8080/:8081/g' {nginx_config}"
        stdin, stdout, stderr = ssh.exec_command(update_cmd)
        print("Port updated")

        # 4. 检查更新后的配置
        print("\n=== Updated Nginx Config ===")
        stdin, stdout, stderr = ssh.exec_command(check_cmd)
        updated_config = stdout.read().decode().strip()
        print(updated_config)

        # 5. 测试配置语法
        print("\n=== Testing Nginx configuration ===")
        test_cmd = "nginx -t 2>&1"
        stdin, stdout, stderr = ssh.exec_command(test_cmd)
        test_output = stdout.read().decode().strip()
        print(test_output)

        if "syntax is ok" in test_output and "test is successful" in test_output:
            # 6. 重启Nginx
            print("\n=== Restarting Nginx ===")
            restart_cmd = "systemctl restart nginx 2>/dev/null || service nginx restart"
            stdin, stdout, stderr = ssh.exec_command(restart_cmd)
            print("Nginx restarted")

            # 7. 检查Nginx状态
            status_cmd = "systemctl status nginx 2>/dev/null | head -5 || echo 'Cannot check status'"
            stdin, stdout, stderr = ssh.exec_command(status_cmd)
            print("Nginx status:", stdout.read().decode().strip())

            # 8. 检查端口监听
            port_cmd = "netstat -ln | grep ':80\\|:443' 2>/dev/null | head -5 || echo 'Cannot check ports'"
            stdin, stdout, stderr = ssh.exec_command(port_cmd)
            print("Web ports:", stdout.read().decode().strip())

            # 9. 测试前端访问
            print("\n=== Testing frontend access ===")
            test_frontend = "curl -s -o /dev/null -w '%{http_code}' http://localhost/ 2>&1 | head -1"
            stdin, stdout, stderr = ssh.exec_command(test_frontend)
            http_code = stdout.read().decode().strip()
            print(f"Frontend HTTP status: {http_code}")

            if http_code == "200" or http_code == "302" or http_code == "304":
                print("\n✅ Frontend should be accessible now!")
            else:
                print("\n⚠️ Frontend may not be accessible (HTTP {})".format(http_code))
        else:
            print("\n❌ Nginx configuration test failed!")

        ssh.close()
        return True

    except Exception as e:
        print(f"SSH failed: {e}")
        return False

if __name__ == "__main__":
    update_nginx_config()
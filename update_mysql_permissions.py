import paramiko
import sys

def update_mysql_permissions():
    host = "47.111.227.125"
    username = "root"
    password = "Cwp521.."

    try:
        ssh = paramiko.SSHClient()
        ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        ssh.connect(host, username=username, password=password, timeout=10)

        # 创建允许从任何主机连接的ai_meeting用户，或修改现有用户
        mysql_commands = [
            "CREATE USER IF NOT EXISTS 'ai_meeting'@'%' IDENTIFIED BY 'Cwp521..';",
            "GRANT ALL PRIVILEGES ON ai_meeting.* TO 'ai_meeting'@'%';",
            "FLUSH PRIVILEGES;"
        ]

        for cmd in mysql_commands:
            full_cmd = f"mysql -u root -p'Cwp521..' -e \"{cmd}\" 2>/dev/null || echo 'Command failed: {cmd}'"
            stdin, stdout, stderr = ssh.exec_command(full_cmd)
            output = stdout.read().decode().strip()
            error = stderr.read().decode().strip()
            print(f"Command: {cmd}")
            if output:
                print(f"Output: {output}")
            if error:
                print(f"Error: {error}")

        # 检查MySQL绑定地址
        check_bind_cmd = "netstat -ln | grep ':3306' 2>/dev/null || ss -ln | grep ':3306' 2>/dev/null || echo 'Cannot check listening ports'"
        stdin, stdout, stderr = ssh.exec_command(check_bind_cmd)
        bind_output = stdout.read().decode().strip()
        print(f"\nMySQL listening ports: {bind_output}")

        # 检查配置文件中的bind-address
        find_bind_cmd = "find /etc/mysql -name '*.cnf' -o -name '*.conf' 2>/dev/null | xargs grep -l 'bind-address' 2>/dev/null | head -5"
        stdin, stdout, stderr = ssh.exec_command(find_bind_cmd)
        config_files = stdout.read().decode().strip()
        print(f"\nConfig files with bind-address: {config_files}")

        if config_files:
            for file in config_files.split('\n'):
                if file:
                    show_cmd = f"grep -n 'bind-address' {file} 2>/dev/null"
                    stdin, stdout, stderr = ssh.exec_command(show_cmd)
                    print(f"{file}: {stdout.read().decode().strip()}")

        ssh.close()
        print("\nMySQL permissions updated. Please test connection.")
        return True
    except Exception as e:
        print(f"SSH connection failed: {e}")
        return False

if __name__ == "__main__":
    update_mysql_permissions()
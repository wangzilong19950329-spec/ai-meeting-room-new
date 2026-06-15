import paramiko
import sys

def check_mysql_permissions():
    host = "47.111.227.125"
    username = "root"
    password = "Cwp521.."

    try:
        ssh = paramiko.SSHClient()
        ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        ssh.connect(host, username=username, password=password, timeout=10)

        # 检查MySQL用户权限
        mysql_cmd = """mysql -u root -p'Cwp521..' -e "SELECT user, host FROM mysql.user WHERE user='ai_meeting';" 2>/dev/null || echo "MySQL command failed" """
        stdin, stdout, stderr = ssh.exec_command(mysql_cmd)
        output = stdout.read().decode().strip()
        error = stderr.read().decode().strip()

        print("MySQL user permissions:")
        print(output)
        if error:
            print("Error:", error)

        # 检查MySQL绑定地址
        config_cmd = "grep -E 'bind-address|skip-networking' /etc/mysql/my.cnf 2>/dev/null || grep -E 'bind-address|skip-networking' /etc/mysql/mysql.conf.d/mysqld.cnf 2>/dev/null || echo 'Config file not found or no bind-address setting'"
        stdin, stdout, stderr = ssh.exec_command(config_cmd)
        output = stdout.read().decode().strip()
        print("\nMySQL bind address config:")
        print(output)

        ssh.close()
        return True
    except Exception as e:
        print(f"SSH connection failed: {e}")
        return False

if __name__ == "__main__":
    check_mysql_permissions()
import paramiko

def open_port():
    host = "47.111.227.125"
    username = "root"
    password = "Cwp521.."

    try:
        ssh = paramiko.SSHClient()
        ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        ssh.connect(host, username=username, password=password, timeout=10)

        # 检查当前iptables规则
        cmd = "iptables -L INPUT -n --line-numbers | grep -E ':8080|:3306' 2>/dev/null || echo 'No rules for 8080 or 3306'"
        stdin, stdout, stderr = ssh.exec_command(cmd)
        print("Current rules:", stdout.read().decode().strip())

        # 添加8080端口规则
        add_cmd = "iptables -A INPUT -p tcp --dport 8080 -j ACCEPT"
        stdin, stdout, stderr = ssh.exec_command(add_cmd)
        print(f"Added rule for port 8080")

        # 保存iptables规则（如果使用iptables-persistent）
        save_cmd = "iptables-save > /etc/iptables/rules.v4 2>/dev/null || echo 'Could not save rules'"
        stdin, stdout, stderr = ssh.exec_command(save_cmd)
        print("Rules saved")

        # 检查端口监听
        check_cmd = "netstat -ln | grep -E ':8080|:3306'"
        stdin, stdout, stderr = ssh.exec_command(check_cmd)
        print("Listening ports:", stdout.read().decode().strip())

        ssh.close()
    except Exception as e:
        print(f"SSH failed: {e}")

if __name__ == "__main__":
    open_port()
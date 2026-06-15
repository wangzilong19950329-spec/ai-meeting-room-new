import paramiko

def check_firewall():
    host = "47.111.227.125"
    username = "root"
    password = "Cwp521.."

    try:
        ssh = paramiko.SSHClient()
        ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        ssh.connect(host, username=username, password=password, timeout=10)

        # 检查防火墙规则
        cmds = [
            "iptables -L -n | grep 3306 2>/dev/null || echo 'No iptables rule for 3306'",
            "ufw status 2>/dev/null | grep -E '3306|Status' || echo 'UFW not active or no specific rule'",
            "netstat -ln | grep 3306",
            "systemctl status firewalld 2>/dev/null | head -5 || echo 'firewalld not active'"
        ]

        for cmd in cmds:
            print(f"\n=== {cmd} ===")
            stdin, stdout, stderr = ssh.exec_command(cmd)
            output = stdout.read().decode().strip()
            if output:
                print(output)
            error = stderr.read().decode().strip()
            if error:
                print(f"Error: {error}")

        ssh.close()
    except Exception as e:
        print(f"SSH failed: {e}")

if __name__ == "__main__":
    check_firewall()
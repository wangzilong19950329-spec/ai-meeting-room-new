import paramiko

def check_server_backend():
    host = "47.111.227.125"
    username = "root"
    password = "Cwp521.."

    try:
        ssh = paramiko.SSHClient()
        ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        ssh.connect(host, username=username, password=password, timeout=10)

        # 检查是否有Java进程运行
        cmd = "ps aux | grep 'ai-meeting-room' | grep -v grep | grep -v python"
        stdin, stdout, stderr = ssh.exec_command(cmd)
        processes = stdout.read().decode().strip()
        if processes:
            print("Running backend processes:")
            print(processes)

            # 获取PID并杀死
            lines = processes.split('\n')
            for line in lines:
                if line:
                    parts = line.split()
                    pid = parts[1]
                    print(f"Killing PID {pid}")
                    ssh.exec_command(f"kill -9 {pid} 2>/dev/null")
        else:
            print("No backend process found")

        # 检查8080端口是否被监听
        port_cmd = "netstat -ln | grep ':8080' 2>/dev/null || ss -ln | grep ':8080' 2>/dev/null || echo 'Port 8080 not listening'"
        stdin, stdout, stderr = ssh.exec_command(port_cmd)
        print("\nPort 8080 status:", stdout.read().decode().strip())

        ssh.close()
    except Exception as e:
        print(f"SSH failed: {e}")

if __name__ == "__main__":
    check_server_backend()
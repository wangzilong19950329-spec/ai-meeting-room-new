import paramiko

def kill_port_8080():
    host = "47.111.227.125"
    username = "root"
    password = "Cwp521.."

    try:
        ssh = paramiko.SSHClient()
        ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        ssh.connect(host, username=username, password=password, timeout=10)

        # 查找占用8080端口的进程
        find_cmd = "lsof -ti:8080 2>/dev/null || ss -tlnp | grep ':8080' | awk '{print $7}' | cut -d= -f2 | cut -d, -f1 | sort -u"
        stdin, stdout, stderr = ssh.exec_command(find_cmd)
        pids = stdout.read().decode().strip()
        print(f"PIDs using port 8080: {pids}")

        if pids:
            for pid in pids.split():
                if pid.isdigit():
                    print(f"Killing PID {pid}")
                    ssh.exec_command(f"kill -9 {pid} 2>/dev/null")
                    # 等待进程终止
                    ssh.exec_command(f"while kill -0 {pid} 2>/dev/null; do sleep 0.1; done; echo 'PID {pid} terminated'")

        # 再次检查端口
        check_cmd = "ss -tln | grep ':8080' 2>/dev/null || echo 'Port 8080 free'"
        stdin, stdout, stderr = ssh.exec_command(check_cmd)
        print("Port 8080 status:", stdout.read().decode().strip())

        # 杀死所有ai-meeting-room相关进程
        kill_java_cmd = "pkill -f 'ai-meeting-room' 2>/dev/null || echo 'No matching processes'"
        stdin, stdout, stderr = ssh.exec_command(kill_java_cmd)
        print("Killed all ai-meeting-room processes")

        ssh.close()
    except Exception as e:
        print(f"SSH failed: {e}")

if __name__ == "__main__":
    kill_port_8080()
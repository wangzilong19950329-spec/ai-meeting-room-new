import paramiko
import os

def compile_on_server():
    host = "47.111.227.125"
    username = "root"
    password = "Cwp521.."

    try:
        ssh = paramiko.SSHClient()
        ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        ssh.connect(host, username=username, password=password, timeout=10)

        # 1. 上传修改后的application.yml
        sftp = ssh.open_sftp()

        # 本地application.yml路径
        local_config = "/Users/wangzilong/IdeaProjects/ai-meeting-room-new/src/main/resources/application.yml"
        remote_config = "/opt/ai-meeting-room/src/main/resources/application.yml"

        try:
            sftp.put(local_config, remote_config)
            print(f"Uploaded application.yml to {remote_config}")
        except Exception as e:
            print(f"Failed to upload config: {e}")
            # 可能目录不存在，先检查
            stdin, stdout, stderr = ssh.exec_command(f"ls -la /opt/ai-meeting-room/src/main/resources/ 2>/dev/null || echo 'Directory not found'")
            print(stdout.read().decode())

        # 2. 重新编译
        print("\nCompiling on server...")
        compile_cmd = "cd /opt/ai-meeting-room && mvn clean package -DskipTests 2>&1 | tail -50"
        stdin, stdout, stderr = ssh.exec_command(compile_cmd)
        output = stdout.read().decode().strip()
        error = stderr.read().decode().strip()

        print("Compilation output (last 50 lines):")
        print(output)
        if error:
            print("Errors:", error)

        # 3. 检查生成的JAR
        jar_cmd = "ls -la /opt/ai-meeting-room/target/ai-meeting-room-*.jar 2>/dev/null || echo 'JAR not found'"
        stdin, stdout, stderr = ssh.exec_command(jar_cmd)
        print("\nJAR files:", stdout.read().decode().strip())

        sftp.close()
        ssh.close()
        return True
    except Exception as e:
        print(f"SSH connection failed: {e}")
        return False

if __name__ == "__main__":
    compile_on_server()
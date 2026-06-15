import paramiko
import os

def download_jar():
    host = "47.111.227.125"
    username = "root"
    password = "Cwp521.."

    try:
        ssh = paramiko.SSHClient()
        ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        ssh.connect(host, username=username, password=password, timeout=10)

        sftp = ssh.open_sftp()

        remote_jar = "/opt/ai-meeting-room/target/ai-meeting-room-1.0.0-SNAPSHOT.jar"
        local_jar = "/Users/wangzilong/IdeaProjects/ai-meeting-room-new/target/ai-meeting-room-1.0.0-SNAPSHOT.jar"

        # 确保本地目录存在
        os.makedirs(os.path.dirname(local_jar), exist_ok=True)

        print(f"Downloading {remote_jar} to {local_jar}")
        sftp.get(remote_jar, local_jar)

        # 检查文件大小
        local_size = os.path.getsize(local_jar)
        print(f"Downloaded JAR size: {local_size} bytes")

        sftp.close()
        ssh.close()
        return True
    except Exception as e:
        print(f"Download failed: {e}")
        return False

if __name__ == "__main__":
    download_jar()
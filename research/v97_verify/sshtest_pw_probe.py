import pty
import os

pid, fd = pty.fork()
if pid == 0:
    os.execvp("ssh", [
        "ssh",
        "-o", "StrictHostKeyChecking=no",
        "-o", "UserKnownHostsFile=/dev/null",
        "-o", "PreferredAuthentications=password",
        "-o", "PubkeyAuthentication=no",
        "-o", "NumberOfPasswordPrompts=2",
        "-p", "2222", "sshtest@127.0.0.1", "echo PASSWORD_LOGIN_OK",
    ])
else:
    out = b""
    sent = 0
    while True:
        try:
            chunk = os.read(fd, 2048)
        except OSError:
            break
        if not chunk:
            break
        out += chunk
        if sent < 2 and b"assword" in out.lower():
            os.write(fd, b"opencode\n")
            sent += 1
            out = b""
    print("PROMPTS_SENT=%d" % sent)
    print(out.decode(errors="replace")[-700:])

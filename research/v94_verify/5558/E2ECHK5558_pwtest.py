import os, pty, select, sys, time

cmd = ["ssh", "-o", "StrictHostKeyChecking=no", "-o", "UserKnownHostsFile=/dev/null",
       "-o", "PreferredAuthentications=keyboard-interactive,password",
       "-o", "PubkeyAuthentication=no", "-p", "22", "sshtest@127.0.0.1", "true"]

pid, fd = pty.fork()
if pid == 0:
    os.execvp("ssh", cmd)

out = b""
sent = False
deadline = time.time() + 25
while time.time() < deadline:
    r, _, _ = select.select([fd], [], [], 1.0)
    if r:
        try:
            chunk = os.read(fd, 4096)
        except OSError:
            break
        if not chunk:
            break
        out += chunk
        if not sent and (b"assword" in out or b"Verification" in out):
            os.write(fd, b"opencode\n")
            sent = True
    else:
        pid_done, status = os.waitpid(pid, os.WNOHANG)
        if pid_done:
            print("EXIT_STATUS", os.waitstatus_to_exitcode(status))
            break

try:
    os.waitpid(pid, 0)
except ChildProcessError:
    pass

txt = out.decode(errors="replace")
print("--- session transcript ---")
print(txt[-1200:])
print("VERDICT:", "AUTH_OK" if "Permission denied" not in txt else "AUTH_DENIED")

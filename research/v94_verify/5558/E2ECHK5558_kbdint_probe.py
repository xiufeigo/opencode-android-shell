import paramiko

t = paramiko.Transport(("127.0.0.1", 22))
t.start_client(timeout=15)
print("methods:", t.auth_none("sshtest"))
try:
    t.auth_interactive("sshtest", lambda name, instr, prompts: ["opencode"] * len(prompts))
    print("KBDINT_AUTH_OK", t.is_authenticated())
except Exception as e:
    print("KBDINT_FAIL", type(e).__name__, e)
t.close()

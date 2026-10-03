import crypt, spwd
h = spwd.getspnam('sshtest').sp_pwdp
print('hash_method:', h.split('$')[1] if h.startswith('$') else 'DES/other')
for c in ['opencode', 'keypass123', 'wrongpw', 'wrongpw ']:
    print(repr(c), 'MATCH' if crypt.crypt(c, h) == h else 'no')

#!/bin/bash
# Ground-truth password check for sshtest.
# 1) debug what mkpasswd generates vs the stored hash
# 2) definitive check via PAM 'su' (success also clears faillock for the user)
H=$(sudo getent shadow sshtest | cut -d: -f2)
SALT=$(echo "$H" | cut -d'$' -f4)
echo "stored : $H"
echo "mkpwd  : $(mkpasswd -m yescrypt -S "$SALT" opencode 2>&1)"
echo

for c in opencode keypass123 wrongpw; do
  if echo "$c" | timeout 10 su - sshtest -c 'id -un' >/dev/null 2>&1; then
    echo "su: '$c'  ->  ACCEPTED"
  else
    echo "su: '$c'  ->  rejected"
  fi
done

echo "--- faillock state after su probes ---"
sudo faillock --user sshtest

#!/bin/bash
# Verify sshtest's password against the STORED hash, reusing the stored salt so the
# comparison is meaningful. No PAM involved -> no faillock side effects.
H=$(sudo getent shadow sshtest | cut -d: -f2)
SALT=$(echo "$H" | cut -d'$' -f4)
echo "hash: ${H:0:12}...  salt: $SALT"

for c in opencode keypass123 wrongpw; do
  mk=$(mkpasswd -m yescrypt -S "$c\$$SALT" 2>/dev/null)
  if [ "$mk" = "$H" ]; then echo "$c  ->  MATCH"
  else echo "$c  ->  no"; fi
done

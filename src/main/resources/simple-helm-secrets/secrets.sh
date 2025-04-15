#/bin/sh

if [[ "$1" == "view" ]]
then
  sed -n '/^sops:/q;p' "$2"
else
  exit 1
fi


#!/usr/bin/env bash
set -e

# Usage: `$ sudo ./install_pricenode_debian.sh`

echo "[*] haveno-pricenode installation script"

##### change as necessary for your system

SYSTEMD_SERVICE_HOME=/etc/systemd/system
SYSTEMD_ENV_HOME=/etc/default

ROOT_USER=root
ROOT_GROUP=root
#ROOT_HOME=/root

PRICENODE_USER=haveno-pricenode
PRICENODE_GROUP=haveno-pricenode
PICENODE_HOME=/home/haveno-pricenode

PRICENODE_REPO_URL=https://github.com/haveno-dex/haveno-pricenode
PRICENODE_REPO_NAME=haveno-pricenode
PRICENODE_REPO_TAG=main
PRICENODE_LATEST_RELEASE=main
PRICENODE_TORHS=haveno-pricenode

TOR_PKG="tor"
#TOR_USER=debian-tor
TOR_GROUP=debian-tor
TOR_CONF=/etc/tor/torrc
TOR_RESOURCES=/var/lib/tor

#####

echo "[*] Upgrading apt packages"
sudo -H -i -u "${ROOT_USER}" DEBIAN_FRONTEND=noninteractive apt-get update -q
sudo -H -i -u "${ROOT_USER}" DEBIAN_FRONTEND=noninteractive apt-get upgrade -qq -y

echo "[*] Installing Haveno dependencies"
sudo -H -i -u "${ROOT_USER}" DEBIAN_FRONTEND=noninteractive apt install -qq -y make wget git openjdk-21-jdk

echo "[*] Installing Tor"
sudo -H -i -u "${ROOT_USER}" DEBIAN_FRONTEND=noninteractive apt-get install -qq -y "${TOR_PKG}"

echo "[*] Adding Tor configuration"
if ! grep "${PRICENODE_TORHS}" /etc/tor/torrc >/dev/null 2>&1;then
  sudo -H -i -u "${ROOT_USER}" sh -c "echo HiddenServiceDir ${TOR_RESOURCES}/${PRICENODE_TORHS}/ >> ${TOR_CONF}"
  sudo -H -i -u "${ROOT_USER}" sh -c "echo HiddenServicePort 80 127.0.0.1:8078 >> ${TOR_CONF}"
  sudo -H -i -u "${ROOT_USER}" sh -c "echo HiddenServiceVersion 3 >> ${TOR_CONF}"
fi

echo "[*] Creating haveno-pricenode user with Tor access"
sudo -H -i -u "${ROOT_USER}" useradd -d "${PICENODE_HOME}" -G "${TOR_GROUP}" "${PRICENODE_USER}"

echo "[*] Creating haveno-pricenode homedir"
sudo -H -i -u "${ROOT_USER}" mkdir -p "${PICENODE_HOME}"
sudo -H -i -u "${ROOT_USER}" chown "${PRICENODE_USER}":"${PRICENODE_GROUP}" ${PICENODE_HOME}

echo "[*] Cloning Pricenode repo"
sudo -H -i -u "${PRICENODE_USER}" git config --global advice.detachedHead false
sudo -H -i -u "${PRICENODE_USER}" git clone --recursive --branch "${PRICENODE_REPO_TAG}" "${PRICENODE_REPO_URL}" "${PICENODE_HOME}/${PRICENODE_REPO_NAME}"

echo "[*] Checking out Pricenode ${PRICENODE_LATEST_RELEASE}"
sudo -H -i -u "${PRICENODE_USER}" sh -c "cd ${PICENODE_HOME}/${PRICENODE_REPO_NAME} && git checkout ${PRICENODE_LATEST_RELEASE}"

echo "[*] Building haveno-pricenode from source"
# Redirect from /dev/null is necessary to workaround gradlew non-interactive shell hanging issue.
sudo -H -i -u "${PRICENODE_USER}" sh -c "cd ${PICENODE_HOME}/${PRICENODE_REPO_NAME} && ./gradlew build  -x test < /dev/null"

echo "[*] Installing haveno-pricenode systemd service"
sudo -H -i -u "${ROOT_USER}" install -c -o "${ROOT_USER}" -g "${ROOT_GROUP}" -m 644 "${PICENODE_HOME}/${PRICENODE_REPO_NAME}/scripts/haveno-pricenode.service" "${SYSTEMD_SERVICE_HOME}"
sudo -H -i -u "${ROOT_USER}" install -c -o "${ROOT_USER}" -g "${ROOT_GROUP}" -m 644 "${PICENODE_HOME}/${PRICENODE_REPO_NAME}/scripts/haveno-pricenode.env" "${SYSTEMD_ENV_HOME}"

echo "[*] Reloading systemd daemon configuration"
sudo -H -i -u "${ROOT_USER}" systemctl daemon-reload

echo "[*] Enabling haveno-pricenode service"
sudo -H -i -u "${ROOT_USER}" systemctl enable haveno-pricenode.service

echo "[*] Starting haveno-pricenode service"
sudo -H -i -u "${ROOT_USER}" systemctl start haveno-pricenode.service
sleep 5
#sudo -H -i -u "${ROOT_USER}" journalctl --no-pager --unit haveno-pricenode

echo "[*] Restarting Tor"
sudo -H -i -u "${ROOT_USER}" service tor restart
sleep 5

echo '[*] Done!'
echo -n '[*] Access your pricenode at http://'
cat "${TOR_RESOURCES}/${PRICENODE_TORHS}/hostname"

exit 0

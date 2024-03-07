# create logrotate configuration that limits size and directory in /var
sudo -s -- <<EOF
touch /etc/logrotate.d/hammerwars
echo "/var/log/hammerwars/*.log {
    size 100M
    rotate 3
}" > /etc/logrotate.d/hammerwars
mkdir -p /var/log/hammerwars
chown -R $USER:$USER /var/log/hammerwars
chmod -R u+rw /var/log/hammerwars
EOF
#build
./gradlew build
# kill any existing screen
screen -S hammerwars -X kill
# run screen to log to /var/log/hammerwars
screen -S hammerwars -L -Logfile /var/log/hammerwars/hammerwars.log -dm ./gradlew run
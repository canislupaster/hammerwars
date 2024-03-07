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
./gradlew build --no-daemon
# kill any existing screen
screen -S hammerwars -X kill
screen -S hammerwars -L -Logfile /var/log/hammerwars/hammerwars.log -dm java -jar ./build/libs/hammerwars-1.0-SNAPSHOT.jar
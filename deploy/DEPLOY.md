# Deploying the MCP server to EC2 — Phase 1 (no HTTPS yet)

Goal: get `demo-0.0.1-SNAPSHOT.jar` running on an EC2 instance as a
systemd service, listening on port 8080. Nginx + HTTPS come in Phase 2.

## 0. Build the JAR (on your laptop)

```bash
cd demo
./mvnw clean package -DskipTests
# -> target/demo-0.0.1-SNAPSHOT.jar
```

## 1. Launch the EC2 instance

- AMI: Amazon Linux 2023
- Type: t3.small (t3.micro works for a demo but is memory-tight)
- Key pair: create/select one for SSH
- Security group:
  - 22 (SSH) -> your IP only
  - 8080 (TEMPORARY, testing only) -> your IP only. Removed in Phase 2.

Allocate an Elastic IP and associate it so the address is stable.

## 2. Install Java 21

```bash
ssh -i your-key.pem ec2-user@<elastic-ip>
sudo dnf install -y java-21-amazon-corretto-headless
java -version   # confirm 21
```

## 3. Copy the JAR up (from your laptop)

```bash
scp -i your-key.pem demo/target/demo-0.0.1-SNAPSHOT.jar ec2-user@<elastic-ip>:/home/ec2-user/
```

On the instance:

```bash
sudo mkdir -p /opt/mcp
sudo mv /home/ec2-user/demo-0.0.1-SNAPSHOT.jar /opt/mcp/app.jar
```

## 4. Install the systemd service

Copy `deploy/mcp.service` to the instance, set a real secret, install it:

```bash
# on the instance
sudo cp mcp.service /etc/systemd/system/mcp.service
# set a strong key (example generator):
sudo sed -i "s/REPLACE_WITH_A_STRONG_SECRET/$(openssl rand -hex 24)/" /etc/systemd/system/mcp.service

sudo systemctl daemon-reload
sudo systemctl enable --now mcp
sudo systemctl status mcp --no-pager
```

## 5. Verify

On the instance:

```bash
curl -s localhost:8080/actuator/health   # {"status":"UP"}
sudo journalctl -u mcp -f                 # tail app logs
```

From your laptop (only works while 8080 is open to your IP):

```bash
curl -s http://<elastic-ip>:8080/actuator/health
```

Prefer an SSH tunnel over opening 8080 at all:

```bash
ssh -i your-key.pem -L 8080:localhost:8080 ec2-user@<elastic-ip>
# then in another terminal:
curl -s http://localhost:8080/actuator/health
```

## Next: Phase 2

Add Nginx as a reverse proxy + Let's Encrypt TLS, then close port 8080
in the security group so the app is only reachable via HTTPS on 443.

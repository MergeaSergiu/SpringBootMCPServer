# Shipping basic EC2 instance logs to CloudWatch

Goal: send the instance's own OS-level logs (system messages, SSH/auth,
boot output) to CloudWatch Logs using the CloudWatch agent. No application
or `mcp.service` changes are required.

Logs collected:

| File | What it contains | CloudWatch log group |
|------|------------------|----------------------|
| `/var/log/messages` | General system / kernel / service messages | `/ec2/mcp/messages` |
| `/var/log/secure` | SSH logins, sudo, auth events | `/ec2/mcp/secure` |
| `/var/log/cloud-init-output.log` | Instance boot / init output | `/ec2/mcp/cloud-init` |
| `/var/log/mcp/app.log` | The MCP app's own logs, as ECS JSON (tool invocations, errors) | `/ec2/mcp/app` |

The app log is written by Spring Boot in ECS JSON format (see `application.yaml`
`logging.structured.format.file: ecs`) once `LOGGING_FILE_NAME` is set in the
`mcp.service` systemd unit. That JSON is what makes CloudWatch Logs Insights
queries by field possible (see the Logs Insights section below).

---

## 1. Give the instance permission (AWS Console)

The agent needs permission to push logs. Attach the AWS-managed policy to the
role already on the instance:

IAM -> **Roles** -> open **`mcp-ec2-role`** -> **Add permissions ->
Attach policies** -> search **`CloudWatchAgentServerPolicy`** ->
**Add permissions.**

The role is already attached to the instance, so this takes effect immediately
(no reboot).

---

## 2. Install the agent (on the instance)

SSH in, then:

```bash
sudo dnf install -y amazon-cloudwatch-agent
```

---

## 3. Write the agent config

Create `/opt/aws/amazon-cloudwatch-agent/etc/amazon-cloudwatch-agent.json`:

```json
{
  "agent": { "run_as_user": "root" },
  "logs": {
    "logs_collected": {
      "files": {
        "collect_list": [
          {
            "file_path": "/var/log/messages",
            "log_group_name": "/ec2/mcp/messages",
            "log_stream_name": "{instance_id}",
            "retention_in_days": 14
          },
          {
            "file_path": "/var/log/secure",
            "log_group_name": "/ec2/mcp/secure",
            "log_stream_name": "{instance_id}",
            "retention_in_days": 14
          },
          {
            "file_path": "/var/log/cloud-init-output.log",
            "log_group_name": "/ec2/mcp/cloud-init",
            "log_stream_name": "{instance_id}",
            "retention_in_days": 14
          },
          {
            "file_path": "/var/log/mcp/app.log",
            "log_group_name": "/ec2/mcp/app",
            "log_stream_name": "{instance_id}",
            "retention_in_days": 14
          }
        ]
      }
    }
  }
}
```

`{instance_id}` auto-names each log stream after the instance. The agent
auto-creates the log groups.

> Tip: to create the file on the instance, `sudo nano <path>` and paste, or
> `sudo tee <path> > /dev/null` and paste + Ctrl-D.

---

## 4. Start the agent with that config

```bash
sudo /opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl \
  -a fetch-config -m ec2 -s \
  -c file:/opt/aws/amazon-cloudwatch-agent/etc/amazon-cloudwatch-agent.json
```

Confirm it's running (also enabled on boot):

```bash
sudo /opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl -a status
# expect: "status": "running"
```

---

## 5. Verify in the console

CloudWatch -> **Logs -> Log groups** -> you should see:

- `/ec2/mcp/messages`
- `/ec2/mcp/secure`
- `/ec2/mcp/cloud-init`

Each has a stream named after the instance ID. Allow a minute after startup.

---

## Troubleshooting

| Symptom | Likely cause / fix |
|---------|--------------------|
| No log groups appear | IAM policy from step 1 not attached. Check the agent log (below) for `AccessDenied`. |
| Agent won't start | Config JSON typo. Re-run the `fetch-config` command; it reports parse errors. |
| Need agent's own logs | `sudo tail -f /opt/aws/amazon-cloudwatch-agent/logs/amazon-cloudwatch-agent.log` |

---

## Querying the app logs (CloudWatch Logs Insights)

Because the app log is ECS JSON, Logs Insights parses each field. In the console:
**CloudWatch -> Logs -> Logs Insights**, pick the `/ec2/mcp/app` log group, and run:

```
# every tool invocation, newest first
fields @timestamp, message
| filter message like /Tool invoked/
| sort @timestamp desc
| limit 100
```

```
# count invocations per tool (which tools get used)
fields message
| filter message like /Tool invoked/
| parse message "Tool invoked: *" as tool
| stats count(*) as calls by tool
| sort calls desc
```

```
# only warnings and errors
fields @timestamp, log.level, message
| filter log.level in ["WARN", "ERROR"]
| sort @timestamp desc
```

(ECS fields: `message`, `log.level`, `log.logger`, `@timestamp`.)

## Notes

- OS logs and the app log are now both shipped. The app log requires the
  `LogsDirectory=mcp` + `LOGGING_FILE_NAME` lines in `deploy/mcp.service` and a
  rebuilt jar (for the `logging.structured.format.file: ecs` setting).
- To also collect CPU / memory / disk **metrics**, add a `metrics` block to the
  same config file and re-run the `fetch-config` command.

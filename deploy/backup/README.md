# 备份自动化与恢复演练（阶段 4 / S-03）

## 1. 安装

```bash
sudo mkdir -p /opt/ilink/scripts /etc/ilink
sudo install -m 750 -o admin -g admin scripts/remote/ilink_backup.py /opt/ilink/scripts/
sudo install -m 750 -o admin -g admin scripts/remote/ilink_restore_drill.py /opt/ilink/scripts/
sudo install -m 750 -o admin -g admin scripts/remote/ilink_backup_check.py /opt/ilink/scripts/

sudo cp deploy/systemd/ilink-backup.service /etc/systemd/system/
sudo cp deploy/systemd/ilink-backup.timer /etc/systemd/system/
sudo cp deploy/systemd/ilink-restore-drill.service /etc/systemd/system/
sudo cp deploy/systemd/ilink-restore-drill.timer /etc/systemd/system/
sudo cp deploy/systemd/ilink-backup-check.service /etc/systemd/system/
sudo cp deploy/systemd/ilink-backup-check.timer /etc/systemd/system/
sudo systemctl daemon-reload
```

## 2. 手动跑一次

```bash
sudo -u admin /usr/bin/python3 /opt/ilink/scripts/ilink_backup.py --purpose pre-stage4
# 确认 manifest.json / SHA256SUMS / uploads.tar / nginx-site.conf 存在
ls -la /opt/ilink/backups/<STAMP>-pre-stage4/
```

## 3. 恢复演练

```bash
sudo -u admin /usr/bin/python3 /opt/ilink/scripts/ilink_restore_drill.py \
  --backup-dir /opt/ilink/backups/<STAMP>-pre-stage4
```

演练会在生产 MySQL 创建 `ilink_chat_restore_drill`，导入后核对核心表并
`CHECK TABLE`，最后在 `finally` 中删除该库。不会触碰 `ilink_chat`。

## 4. 启用定时任务

```bash
sudo systemctl enable --now ilink-backup.timer ilink-restore-drill.timer ilink-backup-check.timer
systemctl list-timers 'ilink-*'
```

## 5. 检查

```bash
python3 /opt/ilink/scripts/ilink_backup_check.py; echo "exit=$?"
journalctl -u ilink-backup.service -n 50 --no-pager
journalctl -u ilink-restore-drill.service -n 50 --no-pager
```

## 6. 凭据

- 脚本从 `/opt/ilink/application-local.properties` 读取 `spring.datasource.password`，
  通过 `MYSQL_PWD` 传给 `mysqldump` / `mysql` 子进程。
- 不创建新的数据库用户，不轮换任何现有密钥，不把密码写入备份目录或日志。
- 备份目录中不应直接落 `application-local.properties`（脚本副本已排除该文件）。

## 7. 回滚

```bash
sudo systemctl disable --now ilink-backup.timer ilink-restore-drill.timer ilink-backup-check.timer
```

已有备份目录保持不变；手工回滚包（`*-stage3-2`、`*-phase-c`）永远不会被自动删除。

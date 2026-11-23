-- Migration: notification event settings
-- Adds the column used by NotificationService and UserSettingsEntity
-- to databases created before the notification-settings stage.

ALTER TABLE user_settings
    ADD COLUMN IF NOT EXISTS notif_event_types VARCHAR(1000) NOT NULL DEFAULT '';

COMMENT ON COLUMN user_settings.notif_event_types
    IS 'Список типов уведомлений через запятую; пустое значение означает все события';

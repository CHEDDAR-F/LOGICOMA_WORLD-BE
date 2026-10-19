# V12 is additive and retained on rollback

Keep memory_images and its bytes when rolling back the application. Dropping it destroys user uploads. Take a verified database backup before release; restore only alongside its matching application when a schema rollback is actually required.

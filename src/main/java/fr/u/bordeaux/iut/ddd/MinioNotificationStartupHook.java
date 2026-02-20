package fr.u.bordeaux.iut.ddd;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.SetBucketNotificationArgs;
import io.minio.messages.EventType;
import io.minio.messages.NotificationConfiguration;
import io.minio.messages.QueueConfiguration;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Arrays;
import java.util.List;

@ApplicationScoped
public class MinioNotificationStartupHook {

    private static final Logger LOG = Logger.getLogger(MinioNotificationStartupHook.class);

    @Inject
    MinioClient minioClient;

    @ConfigProperty(name = "cloudcatcher.minio.notifications.enabled", defaultValue = "true")
    boolean notificationsEnabled;

    @ConfigProperty(name = "cloudcatcher.minio.notifications.bucket", defaultValue = "bucket")
    String bucketName;

    @ConfigProperty(name = "cloudcatcher.minio.notifications.queue-arn", defaultValue = "arn:minio:sqs::RABBIT1:amqp")
    String queueArn;

    @ConfigProperty(name = "cloudcatcher.minio.notifications.prefix", defaultValue = "")
    String objectPrefix;

    @ConfigProperty(name = "cloudcatcher.minio.notifications.suffix", defaultValue = "")
    String objectSuffix;

    void onStart(@Observes StartupEvent ignored) {
        if (!notificationsEnabled) {
            LOG.info("MinIO startup notification hook is disabled");
            return;
        }

        try {
            ensureBucketExists();
            configureBucketNotifications();
            LOG.infof("MinIO bucket notifications configured: bucket=%s arn=%s prefix=%s suffix=%s",
                    bucketName, queueArn, objectPrefix, objectSuffix);
        } catch (Exception e) {
            LOG.error("Failed to configure MinIO bucket notifications at startup", e);
        }
    }

    private void ensureBucketExists() throws Exception {
        boolean bucketExists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
        if (!bucketExists) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
            LOG.infof("Created missing MinIO bucket: %s", bucketName);
        }
    }

    private void configureBucketNotifications() throws Exception {
        QueueConfiguration queue = new QueueConfiguration();
        queue.setQueue(queueArn);
        queue.setEvents(resolveWantedEvents());


        NotificationConfiguration config = new NotificationConfiguration();
        config.setQueueConfigurationList(List.of(queue));

        minioClient.setBucketNotification(
                SetBucketNotificationArgs.builder()
                        .bucket(bucketName)
                        .config(config)
                        .build()
        );
    }

    private List<EventType> resolveWantedEvents() {
        List<EventType> events = List.of(
                EventType.OBJECT_CREATED_ANY,
                EventType.OBJECT_REMOVED_ANY
        );
        LOG.infof("MinIO notification events configured: %s", events);
        return events;
    }
}

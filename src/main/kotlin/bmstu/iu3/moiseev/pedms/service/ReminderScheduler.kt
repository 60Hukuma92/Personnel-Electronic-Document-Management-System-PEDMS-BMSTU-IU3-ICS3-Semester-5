package bmstu.iu3.moiseev.pedms.service

import bmstu.iu3.moiseev.pedms.domain.MarkStatus
import bmstu.iu3.moiseev.pedms.domain.NotificationEvent
import bmstu.iu3.moiseev.pedms.domain.StepStatus
import bmstu.iu3.moiseev.pedms.repository.AcquaintanceMarkRepository
import bmstu.iu3.moiseev.pedms.repository.AppUserRepository
import bmstu.iu3.moiseev.pedms.repository.RouteStepRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

@Component
class ReminderScheduler(
    private val acquaintanceMarkRepository: AcquaintanceMarkRepository,
    private val routeStepRepository: RouteStepRepository,
    private val appUserRepository: AppUserRepository,
    private val notificationService: NotificationService,
    @Value("\${pedms.notifications.reminder-period-hours:24}")
    private val reminderPeriodHours: Long
) {
    @Scheduled(fixedDelayString = "\${pedms.notifications.scheduler-delay-ms:3600000}")
    @Transactional
    fun process() {
        processAcquaintanceReminders()
        processRouteDeadlines()
    }

    private fun processAcquaintanceReminders() {
        val now = LocalDateTime.now()
        val pending = acquaintanceMarkRepository.findAll()
            .filter { it.status == MarkStatus.PENDING }
        pending.forEach { mark ->
            val sheet = mark.sheet ?: return@forEach
            val user = mark.user ?: return@forEach
            val document = sheet.document
            val lastReminder = mark.reminderSentAt
            if (lastReminder == null || Duration.between(lastReminder, now).toHours() >= reminderPeriodHours) {
                notificationService.notify(
                    user,
                    NotificationEvent.ACQUAINTANCE_REMINDER,
                    document,
                    "Напоминание: ознакомьтесь с документом «${document?.title ?: "документ"}»"
                )
                mark.reminderSentAt = now
                acquaintanceMarkRepository.save(mark)
            }
            if (sheet.deadline != null && sheet.deadline!!.isBefore(LocalDate.now().minusDays(7))) {
                user.department?.id?.let { departmentId ->
                    appUserRepository.findManagersOf(departmentId).forEach { manager ->
                        notificationService.notifyIfMissing(
                            manager,
                            NotificationEvent.ACQUAINTANCE_ESCALATED,
                            document,
                            "Просрочено ознакомление с документом «${document?.title ?: "документ"}» более чем на 7 дней"
                        )
                    }
                }
            }
        }
    }

    private fun processRouteDeadlines() {
        val now = LocalDateTime.now()
        routeStepRepository.findOverdue(now).forEach { step ->
            val user = step.user ?: return@forEach
            notificationService.notifyIfMissing(
                user,
                NotificationEvent.DEADLINE_EXPIRED,
                step.document,
                "Нарушен срок этапа согласования документа «${step.document?.title ?: "документ"}»"
            )
        }
        routeStepRepository.findAll()
            .filter { it.status == StepStatus.PENDING || it.status == StepStatus.IN_PROGRESS }
                .filter { it.deadline != null && it.deadline!!.isAfter(now) && Duration.between(now, it.deadline).toHours() <= 24 }
                .forEach { step ->
                    val user = step.user ?: return@forEach
                    notificationService.notifyIfMissing(
                        user,
                        NotificationEvent.DEADLINE_NEAR,
                        step.document,
                        "Приближается срок этапа согласования документа «${step.document?.title ?: "документ"}»"
                    )
                }
    }
}

package bmstu.iu3.moiseev.pedms.service

import bmstu.iu3.moiseev.pedms.domain.DocCategory
import bmstu.iu3.moiseev.pedms.domain.DocStatus
import bmstu.iu3.moiseev.pedms.entity.AppUserEntity
import bmstu.iu3.moiseev.pedms.entity.DocTypeEntity
import bmstu.iu3.moiseev.pedms.entity.DocumentEntity
import bmstu.iu3.moiseev.pedms.repository.AuditLogRepository
import bmstu.iu3.moiseev.pedms.repository.DocTypeRepository
import bmstu.iu3.moiseev.pedms.repository.DocumentRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.mock.web.MockMultipartFile
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.SimpleTransactionStatus
import java.time.LocalDate
import java.util.Optional

class OrderFormationServiceTests {
    private lateinit var documentRepository: DocumentRepository
    private lateinit var docTypeRepository: DocTypeRepository
    private lateinit var auditLogRepository: AuditLogRepository
    private lateinit var currentUserService: CurrentUserService
    private lateinit var routingService: RoutingService
    private lateinit var transactionManager: PlatformTransactionManager
    private lateinit var service: OrderFormationService
    private lateinit var hrUser: AppUserEntity
    private lateinit var applicationType: DocTypeEntity
    private lateinit var orderType: DocTypeEntity

    @BeforeEach
    fun setUp() {
        documentRepository = mock(DocumentRepository::class.java)
        docTypeRepository = mock(DocTypeRepository::class.java)
        auditLogRepository = mock(AuditLogRepository::class.java)
        currentUserService = mock(CurrentUserService::class.java)
        routingService = mock(RoutingService::class.java)
        transactionManager = mock(PlatformTransactionManager::class.java)
        `when`(transactionManager.getTransaction(any(TransactionDefinition::class.java)))
            .thenAnswer { SimpleTransactionStatus() }

        hrUser = AppUserEntity(id = 3, fullName = "Сидорова Мария Петровна", email = "sidorova@pedms.ru")
        applicationType = DocTypeEntity(id = 1, code = "ZAYAVL_OTP", name = "Заявление на отпуск", category = DocCategory.APPLICATION)
        orderType = DocTypeEntity(id = 2, code = "PRIKAZ_OTP", name = "Приказ о отпуске", category = DocCategory.ORDER)

        `when`(currentUserService.requireCurrentUser()).thenReturn(hrUser)
        `when`(docTypeRepository.findAllByIsActiveTrueOrderByNameAsc())
            .thenReturn(listOf(applicationType, orderType))
        `when`(documentRepository.findAllByStatusOrderByCreatedAtDesc(DocStatus.DRAFT)).thenReturn(emptyList())
        `when`(documentRepository.findAccessibleTo(3)).thenReturn(emptyList())

        service = OrderFormationService(
            documentRepository,
            docTypeRepository,
            auditLogRepository,
            currentUserService,
            routingService,
            transactionManager
        )
    }

    @Test
    fun `createFromApplication copies fields and sets baseDoc`() {
        val employee = AppUserEntity(id = 7, fullName = "Петрова Анна Сергеевна", email = "petrova@pedms.ru")
        val application = DocumentEntity(
            id = 10,
            type = applicationType,
            author = employee,
            number = "З-2026-0001",
            title = "Заявление Петровой",
            status = DocStatus.APPROVED,
            contentJson = mapOf(
                "start_date" to "2026-12-10",
                "end_date" to "2026-12-20",
                "comment" to "Семейные обстоятельства"
            ),
            periodStart = LocalDate.parse("2026-12-10"),
            periodEnd = LocalDate.parse("2026-12-20")
        )
        `when`(documentRepository.findById(10)).thenReturn(Optional.of(application))
        `when`(documentRepository.save(any(DocumentEntity::class.java))).thenAnswer { invocation ->
            val saved = invocation.arguments[0] as DocumentEntity
            saved.id = 20
            saved
        }

        val order = service.createFromApplication(10)

        assertEquals(application, order.baseDoc)
        assertEquals(DocStatus.DRAFT, order.status)
        assertEquals("Заявление № З-2026-0001", order.contentJson["order_basis"])
        assertEquals("2026-12-10 — 2026-12-20", order.contentJson["order_period"])
        assertEquals("Петрова Анна Сергеевна", order.contentJson["employee_full_name"])
    }

    @Test
    fun `createFromApplication rejects unapproved application`() {
        val application = DocumentEntity(
            id = 11,
            type = applicationType,
            author = hrUser,
            title = "Черновик заявления",
            status = DocStatus.DRAFT
        )
        `when`(documentRepository.findById(11)).thenReturn(Optional.of(application))

        val exception = assertThrows(IllegalArgumentException::class.java) {
            service.createFromApplication(11)
        }

        assertEquals(
            "Приказ можно сформировать только из согласованного заявления",
            exception.message
        )
    }

    @Test
    fun `batch import creates only valid rows and reports errors`() {
        val application = DocumentEntity(
            id = 12,
            type = applicationType,
            author = hrUser,
            title = "Согласованное заявление",
            status = DocStatus.APPROVED
        )
        `when`(documentRepository.findById(12)).thenReturn(Optional.of(application))
        `when`(documentRepository.findById(999)).thenReturn(Optional.empty())
        `when`(documentRepository.save(any(DocumentEntity::class.java))).thenAnswer { invocation ->
            val saved = invocation.arguments[0] as DocumentEntity
            saved.id = 30
            saved
        }

        val file = MockMultipartFile(
            "file",
            "orders.csv",
            "text/csv",
            "ID заявления\r\n12\r\n999\r\n".toByteArray(Charsets.UTF_8)
        )

        val report = service.importOrders(file)

        assertEquals(2, report.totalRows)
        assertEquals(1, report.createdRows)
        assertEquals(1, report.failedRows)
        assertEquals(30, report.rows.first().documentId)
    }
}

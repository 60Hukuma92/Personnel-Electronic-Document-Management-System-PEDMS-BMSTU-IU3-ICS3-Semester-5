package bmstu.iu3.moiseev.pedms.service

import bmstu.iu3.moiseev.pedms.domain.DocCategory
import bmstu.iu3.moiseev.pedms.domain.DocStatus
import bmstu.iu3.moiseev.pedms.dto.DocumentForm
import bmstu.iu3.moiseev.pedms.dto.DocumentValidationException
import bmstu.iu3.moiseev.pedms.entity.AppUserEntity
import bmstu.iu3.moiseev.pedms.entity.DocTypeEntity
import bmstu.iu3.moiseev.pedms.entity.DocumentEntity
import bmstu.iu3.moiseev.pedms.repository.AttachmentRepository
import bmstu.iu3.moiseev.pedms.repository.DocTypeRepository
import bmstu.iu3.moiseev.pedms.repository.DocumentRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.Optional

class DocumentServiceTests {
    private lateinit var documentRepository: DocumentRepository
    private lateinit var docTypeRepository: DocTypeRepository
    private lateinit var attachmentRepository: AttachmentRepository
    private lateinit var currentUserService: CurrentUserService
    private lateinit var documentService: DocumentService
    private lateinit var user: AppUserEntity
    private lateinit var type: DocTypeEntity

    @BeforeEach
    fun setUp() {
        documentRepository = mock(DocumentRepository::class.java)
        docTypeRepository = mock(DocTypeRepository::class.java)
        attachmentRepository = mock(AttachmentRepository::class.java)
        currentUserService = mock(CurrentUserService::class.java)
        documentService = DocumentService(
            documentRepository,
            docTypeRepository,
            attachmentRepository,
            currentUserService
        )
        user = AppUserEntity(id = 7, fullName = "Тестовый Сотрудник", email = "test@pedms.ru")
        type = DocTypeEntity(
            id = 3,
            code = "TEST",
            name = "Тестовый документ",
            category = DocCategory.MEMO,
            templateJson = mapOf(
                "fields" to listOf(
                    mapOf(
                        "key" to "memo_subject",
                        "type" to "text",
                        "label" to "Тема",
                        "required" to true,
                        "halfWidth" to false
                    )
                )
            )
        )
        `when`(currentUserService.requireCurrentUser()).thenReturn(user)
        `when`(docTypeRepository.findById(3)).thenReturn(Optional.of(type))
    }

    @Test
    fun `my documents returns only documents authored by current user`() {
        val ownDocument = DocumentEntity(
            id = 21,
            type = type,
            author = user,
            title = "Моё заявление",
            status = DocStatus.DRAFT
        )
        val foreignAuthor = AppUserEntity(
            id = 8,
            fullName = "Другой Сотрудник",
            email = "other@pedms.ru"
        )
        val foreignDocument = DocumentEntity(
            id = 22,
            type = type,
            author = foreignAuthor,
            title = "Чужое заявление",
            status = DocStatus.DRAFT
        )
        `when`(documentRepository.findAllByAuthorIdOrderByCreatedAtDesc(7))
            .thenReturn(listOf(ownDocument))
        `when`(documentRepository.findAccessibleTo(7))
            .thenReturn(listOf(ownDocument, foreignDocument))

        val result = documentService.findList(null, null, null, null, null)

        assertEquals(listOf(ownDocument), result)
    }

    @Test
    fun `create rejects missing required template field`() {
        val exception = assertThrows(DocumentValidationException::class.java) {
            documentService.create(
                DocumentForm(
                    typeId = 3,
                    title = "Служебная записка",
                    fields = linkedMapOf("memo_subject" to "")
                )
            )
        }

        assertEquals("Заполните поле «Тема»", exception.fieldErrors["memo_subject"])
    }

    @Test
    fun `create stores draft with template data`() {
        `when`(documentRepository.save(org.mockito.ArgumentMatchers.any(DocumentEntity::class.java)))
            .thenAnswer { invocation ->
                val document = invocation.arguments[0] as DocumentEntity
                document.id = 42
                document
            }

        val document = documentService.create(
            DocumentForm(
                typeId = 3,
                title = "Служебная записка",
                fields = linkedMapOf("memo_subject" to "Командировка")
            )
        )

        assertEquals(DocStatus.DRAFT, document.status)
        assertEquals("Командировка", document.contentJson["memo_subject"])
        assertEquals(user, document.author)
    }

    @Test
    fun `recall rejects document outside approval`() {
        val document = DocumentEntity(
            id = 11,
            type = type,
            author = user,
            title = "Черновик",
            status = DocStatus.DRAFT
        )
        `when`(documentRepository.findById(11)).thenReturn(Optional.of(document))

        val exception = assertThrows(IllegalStateException::class.java) {
            documentService.recall(11)
        }

        assertEquals("Отозвать можно только документ, находящийся на согласовании", exception.message)
    }

    @Test
    fun `update rejects signed document`() {
        val document = DocumentEntity(
            id = 12,
            type = type,
            author = user,
            title = "Подписанный документ",
            status = DocStatus.SIGNED
        )
        `when`(documentRepository.findById(12)).thenReturn(Optional.of(document))

        val exception = assertThrows(IllegalStateException::class.java) {
            documentService.update(
                12,
                DocumentForm(
                    typeId = 3,
                    title = "Изменённое название",
                    fields = linkedMapOf("memo_subject" to "Изменение")
                )
            )
        }

        assertEquals("Подписанный или отправленный документ нельзя редактировать", exception.message)
    }
}

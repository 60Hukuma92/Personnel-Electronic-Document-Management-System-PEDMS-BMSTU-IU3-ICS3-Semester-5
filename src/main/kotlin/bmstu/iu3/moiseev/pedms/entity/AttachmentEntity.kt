package bmstu.iu3.moiseev.pedms.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * Вложение к кадровому документу (FR220).
 *
 * Ограничения, обеспечиваемые базой данных:
 *   • размер одного файла — не более 25 МБ (CHECK-условие ck_attachment_size);
 *   • суммарный размер вложений одного документа — не более 100 МБ
 *     (триггер trg_check_attachment_total_size);
 *   • исполняемые файлы запрещены (CHECK-условие ck_attachment_type).
 *
 * Уровень данных (раздел 3.7 записки).
 */
@Entity
@Table(name = "attachment")
class AttachmentEntity(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "attach_id")
    var id: Int? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "doc_id", nullable = false)
    var document: DocumentEntity? = null,

    /** Пользователь, загрузивший файл. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "uploaded_by", nullable = false)
    var uploadedBy: AppUserEntity? = null,

    @Column(name = "file_name", nullable = false, length = 255)
    var fileName: String = "",

    /** Путь к файлу в каталоге вложений. */
    @Column(name = "file_path", nullable = false, length = 500)
    var filePath: String = "",

    /** Размер файла в байтах. */
    @Column(name = "file_size", nullable = false)
    var fileSize: Int = 0,

    @Column(name = "file_type", length = 120)
    var fileType: String? = null,

    @Column(name = "uploaded_at", nullable = false)
    var uploadedAt: LocalDateTime = LocalDateTime.now()
)

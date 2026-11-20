/*
 * Вспомогательные скрипты интерфейса ИС КЭДО.
 *
 * Назначение: подтверждение необратимых действий (удаление учётной записи,
 * удаление документа) и мелкие удобства — автоотправка фильтров, отметка
 * просмотренных уведомлений.
 */
(function () {
    'use strict';

    /**
     * Подтверждение опасного действия.
     *
     * Использование: <form data-confirm="Точно удалить?"> —
     * если атрибут задан, отправка формы требует подтверждения.
     */
    document.addEventListener('submit', function (event) {
        var form = event.target;
        if (!(form instanceof HTMLFormElement)) {
            return;
        }
        var message = form.getAttribute('data-confirm');
        if (message && !window.confirm(message)) {
            event.preventDefault();
        }
    });

    /**
     * Автоотправка формы при изменении элемента выбора —
     * применяется к фильтрам реестра.
     */
    document.addEventListener('change', function (event) {
        var element = event.target;
        if (!(element instanceof HTMLElement)) {
            return;
        }
        if (element.hasAttribute('data-auto-submit') && element.form) {
            element.form.submit();
        }
    });
})();

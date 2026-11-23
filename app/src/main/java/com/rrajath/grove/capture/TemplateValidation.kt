package com.rrajath.grove.capture

import com.rrajath.grove.ui.util.UiText

/** Validation state for one [CaptureTemplate]: target-filename + placeholder checks. */
data class TemplateIssues(
    /** [CaptureTemplate.targetFile] error (PLAIN kind only; always null for ROAM_NODE). */
    val filenameError: UiText? = null,
    /** [CaptureTemplate.roamDirectory] error (ROAM_NODE kind only). */
    val directoryError: UiText? = null,
    /** [CaptureTemplate.filenamePattern] error (ROAM_NODE kind only). */
    val filenamePatternError: UiText? = null,
    /** Distinct unsupported `%...` tokens found in the template body. */
    val invalidPlaceholders: List<String>,
) {
    val count: Int get() =
        listOfNotNull(filenameError, directoryError, filenamePatternError).size + invalidPlaceholders.size
    val hasErrors: Boolean get() = count > 0
}

object TemplateValidator {
    fun validate(template: CaptureTemplate): TemplateIssues = when (template.kind) {
        TemplateKind.PLAIN -> validate(template.targetFile, template.template)
        TemplateKind.ROAM_NODE -> TemplateIssues(
            directoryError = FilenameValidation.errorForDirectory(template.roamDirectory),
            filenamePatternError = FilenamePattern.errorFor(template.filenamePattern),
            invalidPlaceholders =
                PlaceholderExpander.findInvalid(template.newFileTemplate).map { it.token }.distinct(),
        )
    }

    fun validate(targetFile: String, templateText: String): TemplateIssues = TemplateIssues(
        filenameError = FilenameValidation.errorFor(targetFile),
        invalidPlaceholders = PlaceholderExpander.findInvalid(templateText).map { it.token }.distinct(),
    )
}

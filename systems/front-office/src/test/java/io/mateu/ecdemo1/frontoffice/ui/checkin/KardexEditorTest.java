package io.mateu.ecdemo1.frontoffice.ui.checkin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KardexEditorTest {

  @Test
  void onlyANewValueIsAChange() {
    assertThat(DocumentoView.changed("RC12345678", " rc12345678 ")).isFalse();
    assertThat(DocumentoView.changed(null, "RC12345678")).isTrue();
    assertThat(DocumentoView.changed("RC12345678", "")).isFalse();
  }

  @Test
  void theIssuingCountryAndTheResponsibleAdultShowOnlyWhenARuleAsksForThem() {
    var editor = new DocumentoView.DocumentoEditor();
    editor.setRequeridos("NATIONALITY");
    assertThat(editor.isHidden("paisExpedicion", null)).isTrue();
    assertThat(editor.isHidden("tutor", null)).isTrue();
    assertThat(editor.isHidden("nacionalidad", null)).isFalse();
    editor.setRequeridos("DOCUMENT_ISSUING_COUNTRY,GUARDIAN");
    assertThat(editor.isHidden("paisExpedicion", null)).isFalse();
    assertThat(editor.isHidden("tutor", null)).isFalse();
  }
}

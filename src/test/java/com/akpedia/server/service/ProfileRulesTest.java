package com.akpedia.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.akpedia.server.exception.InvalidUserException;

class ProfileRulesTest {

    @Test
    @DisplayName("a CPF is cleaned of dots, dashes and spaces and kept when its check digits are right")
    void validCpf() {
        assertThat(ProfileRules.cpf(" 529.982.247-25 ", InvalidUserException::new)).isEqualTo("52998224725");
        assertThat(ProfileRules.cpf("12345678909", InvalidUserException::new)).isEqualTo("12345678909");
        assertThat(ProfileRules.hasValidCheckDigits("11144477735")).isTrue();
    }

    @Test
    @DisplayName("a CPF with a wrong check digit, or of one repeated digit, is refused")
    void invalidCpf() {
        assertThat(ProfileRules.hasValidCheckDigits("52998224724")).isFalse();
        assertThat(ProfileRules.hasValidCheckDigits("52998224715")).isFalse();
        assertThat(ProfileRules.hasValidCheckDigits("00000000000")).isFalse();
        assertThat(ProfileRules.hasValidCheckDigits("11111111111")).isFalse();
        assertThatThrownBy(() -> ProfileRules.cpf("123.456.789-01", InvalidUserException::new))
                .isInstanceOf(InvalidUserException.class).hasMessage("CPF inválido: confira os dígitos.");
        assertThatThrownBy(() -> ProfileRules.cpf("1234567890", InvalidUserException::new))
                .isInstanceOf(InvalidUserException.class).hasMessageContaining("11 dígitos");
    }

    @Test
    @DisplayName("the sector must be one of the realm's seven")
    void sectors() {
        assertThat(ProfileRules.sector(" Recursos Humanos ", InvalidUserException::new)).isEqualTo("Recursos Humanos");
        assertThatThrownBy(() -> ProfileRules.sector("Setor 1", InvalidUserException::new))
                .isInstanceOf(InvalidUserException.class);
    }
}

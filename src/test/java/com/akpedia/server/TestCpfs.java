package com.akpedia.server;

import java.util.concurrent.ThreadLocalRandom;

/** Valid CPFs for tests, random so tests on the shared database do not collide. */
public final class TestCpfs {

    private TestCpfs() {
    }

    /** 11 digits with valid check digits. */
    public static String random() {
        StringBuilder cpf = new StringBuilder();
        do {
            cpf.setLength(0);
            for (int i = 0; i < 9; i++) {
                cpf.append(ThreadLocalRandom.current().nextInt(10));
            }
        } while (cpf.chars().distinct().count() == 1);
        cpf.append(checkDigit(cpf, 9));
        cpf.append(checkDigit(cpf, 10));
        return cpf.toString();
    }

    private static int checkDigit(CharSequence cpf, int length) {
        int sum = 0;
        for (int i = 0; i < length; i++) {
            sum += (cpf.charAt(i) - '0') * (length + 1 - i);
        }
        int rest = sum * 10 % 11;
        return rest == 10 ? 0 : rest;
    }
}

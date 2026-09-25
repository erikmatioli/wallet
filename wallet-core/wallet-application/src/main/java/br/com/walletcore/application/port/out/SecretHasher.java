package br.com.walletcore.application.port.out;

public interface SecretHasher {

    String hash(String rawSecret);
}

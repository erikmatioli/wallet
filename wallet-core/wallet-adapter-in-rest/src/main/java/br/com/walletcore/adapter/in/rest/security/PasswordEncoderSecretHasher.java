package br.com.walletcore.adapter.in.rest.security;

import br.com.walletcore.application.port.out.SecretHasher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Client secrets are stored only as salted adaptive hashes (bcrypt via the delegating encoder). */
@Component
class PasswordEncoderSecretHasher implements SecretHasher {

    private final PasswordEncoder encoder;

    PasswordEncoderSecretHasher(PasswordEncoder encoder) {
        this.encoder = encoder;
    }

    @Override
    public String hash(String rawSecret) {
        return encoder.encode(rawSecret);
    }
}

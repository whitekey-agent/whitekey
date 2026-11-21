package ai.whitekey.controller;

import ai.whitekey.proxy.ProxyCertificateAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CaController {

    private final ProxyCertificateAuthority certificateAuthority;

    public CaController(ProxyCertificateAuthority certificateAuthority) {
        this.certificateAuthority = certificateAuthority;
    }

    @GetMapping(value = "/ca.crt", produces = "application/x-pem-file")
    public String getCaCertificate() {
        return certificateAuthority.getCaCertificatePem();
    }
}

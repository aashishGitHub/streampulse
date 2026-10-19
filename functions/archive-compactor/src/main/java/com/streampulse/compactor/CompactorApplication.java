/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.compactor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CompactorApplication {

    public static void main(String[] args) {
        SpringApplication.run(CompactorApplication.class, args);
    }
}

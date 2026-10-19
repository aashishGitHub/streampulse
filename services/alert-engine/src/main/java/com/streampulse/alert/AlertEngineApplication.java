/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AlertEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(AlertEngineApplication.class, args);
    }
}

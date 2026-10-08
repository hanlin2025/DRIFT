package com.drift.backend.account.passwordreset;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

@Configuration
public class PasswordResetMailConfig {

	@Bean
	@ConditionalOnProperty("spring.mail.host")
	public PasswordResetMailer smtpPasswordResetMailer(JavaMailSender mailSender,
			@Value("${app.password-reset.from}") String from) {
		return new SmtpPasswordResetMailer(mailSender, from);
	}

	static final class SmtpPasswordResetMailer implements PasswordResetMailer {

		private final JavaMailSender mailSender;
		private final String from;

		SmtpPasswordResetMailer(JavaMailSender mailSender, String from) {
			this.mailSender = mailSender;
			this.from = from;
		}

		@Override
		public void sendResetLink(String email, String resetLink, Duration validFor) {
			SimpleMailMessage message = new SimpleMailMessage();
			message.setFrom(from);
			message.setTo(email);
			message.setSubject("Reset your DRIFT password");
			message.setText("""
					A password reset was requested for your DRIFT account.
					Open this link to choose a new password. It expires in %s.
					%s

					If you did not request this, you can ignore this email.
					""".formatted(describe(validFor), resetLink));
			mailSender.send(message);
		}
	}

	static String describe(Duration validFor) {
		long hours = validFor.toHours();
		if (hours > 0 && validFor.equals(Duration.ofHours(hours))) {
			return hours == 1 ? "1 hour" : hours + " hours";
		}
		long minutes = validFor.toMinutes();
		if (minutes > 0 && validFor.equals(Duration.ofMinutes(minutes))) {
			return minutes == 1 ? "1 minute" : minutes + " minutes";
		}
		return validFor.toString();
	}
}

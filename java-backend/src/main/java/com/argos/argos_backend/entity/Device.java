package com.argos.argos_backend.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "devices")
public class Device {	
	
	
		@Id
		@GeneratedValue(strategy = GenerationType.IDENTITY)
		private long id;
		
		@Column(nullable = false, unique = true, length = 255)
		private String deviceId;
		
		@Column(length = 100)
		private String deviceName;
		
		@Column(nullable = false, unique = true, length = 255)
		private String accessToken;
		
		@Column(nullable = false)
		private LocalDateTime createdAt;
		
		@Column(nullable = false)
		private LocalDateTime lastSeenAt;

		
		public Device() {
			
			
		}
		
        public  Device(String deviceId, String deviceName, String accessToken) {
        	
			this.deviceId = deviceId;
			this.deviceName = deviceName;
			this.accessToken = accessToken;
			
		}
        
        @PrePersist
        public void onCreate(){
        	LocalDateTime now = LocalDateTime.now();
        	
        	if(createdAt == null){
        		createdAt = now;
        	}
        	if(lastSeenAt == null){
        		lastSeenAt = now;
        	}
        }

		public long getId() {
			return id;
		}

		public void setId(long id) {
			this.id = id;
		}

		public String getDeviceId() {
			return deviceId;
		}

		public void setDeviceId(String deviceId) {
			this.deviceId = deviceId;
		}

		public String getDeviceName() {
			return deviceName;
		}

		public void setDeviceName(String deviceName) {
			this.deviceName = deviceName;
		}

		public String getAccessToken() {
			return accessToken;
		}

		public void setAccessToken(String accessToken) {
			this.accessToken = accessToken;
		}

		public LocalDateTime getCreatedAt() {
			return createdAt;
		}

		public void setCreatedAt(LocalDateTime createdAt) {
			this.createdAt = createdAt;
		}

		public LocalDateTime getLastSeenAt() {
			return lastSeenAt;
		}

		public void setLastSeenAt(LocalDateTime lastSeenAt) {
			this.lastSeenAt = lastSeenAt;
		}
        
        
        
        
        
		
		
		
}


---
**Title:** Legacy Ubuntu to Kubernetes Migration  
**Author:** Daniel G  
**Status:** In Progress  

---
# Executive Summary

This project documents the migration of a legacy Spring Boot application from
an Ubuntu 18.04 virtual machine to a containerized Kubernetes platform. Source
control is versioned in GitHub, and the migration replaced manual, host-based
deployments with a CI-driven workflow using Jenkins, Docker, Amazon ECR, and Amazon EKS.

The application deployment model was redesigned to follow cloud-native principles, including
immutable container images, externalized configuration, and a decoupled stateful
dependency (PostgreSQL). Kubernetes was used to manage application
lifecycle, health checks, and scaling behavior.

This work demonstrates practical experience modernizing legacy systems,
building automated deployment pipelines, and operating containerized workloads
in a production-like Kubernetes environment.

---
# Problem Statement

The application was deployed as a Spring Boot service running directly on an
Ubuntu 18.04 virtual machine with manual deployment processes. Application
configuration and runtime behavior were tightly coupled to the host, making
the system difficult to reproduce and maintain consistently.

The service depended on a single external PostgreSQL database, which was
accessed directly from the host environment. This coupling, combined with
manual deployments and an aging operating system, introduced operational risk,
limited scalability, and made it difficult to adopt modern deployment and automation
practices.

---
# Goals and Non-Goals
## Goals

- Migrate a legacy Spring Boot application from an Ubuntu 18.04 virtual
  machine to a containerized, Kubernetes-based deployment model.
- Introduce a repeatable and automated build and deployment process using
  CI tools instead of manual deployments and one-off configuration changes.
- Keep the database separate from the application while moving PostgreSQL to
  Amazon RDS to reduce operational overhead and clearly separate database
  management from application deployments.
- Externalize configuration and secrets to support immutable container images
  and environment-specific deployments.
- Store configuration and credentials outside the container image so the same
  image can be reused across environments without modification.
- Deploy and operate the application in a production-like Kubernetes
  environment to mirror real-world platform engineering workflows.
- Deploy and operate the application on an Amazon EKS cluster, using Kubernetes
  deployments, services, and health checks to reflect a real production environment. 
## Non-Goals

- Changing how the application works internally or breaking it into multiple smaller services
- Changing how the database is structured internally, beyond moving it to Amazon RDS and keeping it compatible with the existing application.
- Ensuring deployments never cause brief interruptions during the first migration.
- Setting up advanced monitoring, scaling, or networking features that are not required initially.
- Optimizing application performance to make it faster than it was in the legacy setup.

---
# Target Architecture

The target architecture moves the application from a host-bound deployment
model to a containerized, Kubernetes-managed workload. The Spring Boot
application is packaged as a Docker image, built and published through an
automated CI pipeline, and deployed onto Amazon EKS. PostgreSQL remains an
external dependency and is provided as a managed Amazon RDS service, accessed 
by the application over the network.

## High-Level Components

- **CI (Jenkins):** Builds the application, runs the container build, and
  publishes versioned images.
- **Container Registry (Amazon ECR):** Stores immutable application images for
  deployment.
- **Kubernetes Runtime (Amazon EKS):** Runs the application using Kubernetes
  primitives (Deployments, Services, health checks).
- **External Dependency (Amazon RDS for PostgreSQL):** A managed PostgreSQL
  database service accessible from the application workload over the network.

## Deployment Flow

1. A source change on GitHub repository.
2. Jenkins builds the Spring Boot artifact and produces a Docker image.
3. The image is pushed to Amazon ECR with a unique tag.
4. Kubernetes deploys the new image to EKS using a `Deployment` rollout.
5. A `Service` provides stable in-cluster connectivity to the application.
6. The application connects to Amazon RDS for PostgreSQL using environment-provided
   connection parameters (host, port, database, username, password).

## Kubernetes Workload Design

- **Deployment:** Manages replica count, rolling updates, and self-healing.
- **Service:** Provides a stable virtual IP/DNS name for the application.
- **Health Checks:** Readiness and liveness probes ensure traffic is only sent
  to healthy pods and failed pods are restarted.
- **Configuration & Secrets:** Non-sensitive configuration is injected via
  ConfigMaps. Database credentials and connection details are injected via
  Secrets credentials are injected via Secrets (or an external secrets
  mechanism), keeping the container image immutable.

## External Dependency Boundary (Amazon RDS for PostgreSQL)

PostgreSQL is treated as an external system with a clear boundary:
Amazon RDS for PostgreSQL is treated as an external managed system with a clear
operational boundary.
- The application pods are stateless and can be replaced at any time without
  impacting persisted data.
- Database connectivity is provided via a stable RDS endpoint and managed
  credentials supplied at runtime.
- Database concerns (backups, patching, storage durability, high availability) are handled
  by Amazon RDS and are separatedd from the application deployment lifecycle.

## Desired Outcomes

- Repeatable builds and deployments via CI.
- Reduced changes in configuration through immutable container images and 
  externalized configuration.
- Improved resiliency via Kubernetes self-healing and rolling deployments.
- Clear separation between stateless application and a managed,
  stateful database service.

## Architecture Diagram
```text
Developer Commit (GitHub)
        |
        v
Jenkins CI (build / test / package)
        |
        v
Docker Image Build
        |
        v
Amazon ECR (Container Registry)
        |
        v
Amazon EKS (Kubernetes)
  ├─ Deployment (Spring Boot Pods)
  ├─ Service (Stable In-Cluster Endpoint)
  └─ ConfigMap / Secret (DB Connection Settings)
        |
        |  TCP 5432 (VPC Networking / Security Groups)
        v
Amazon RDS for PostgreSQL
  ├─ Managed Storage & Backups
  └─ Patching / Maintenance by AWS
```

---
# Legacy Environment (Build Guide)
## 1. Legacy Application Host
### 1.1 Create the VM
A virtual machine was created in Proxmox to represent the legacy application
host. The VM was configured to closely match the original deployment environment,
using Ubuntu 18.04 as the operating system. Resources were allocated to be
sufficient for running a single Spring Boot application instance without
introducing artificial constraints.

This environment provides a baseline for comparing the legacy deployment model
with the containerized Kubernetes-based approach.
### 1.2 Baseline OS configuration
After we create and provision the VM, the operating system needs to be prepared
for use as a long-running application host. SSH access needs to be enabled to allow 
remote administration and the `qemu-guest-agent` was installed and activated to 
support improved VM management and visibility from Proxmox.

Additionally, the system was updated to ensure a consistent and stable baseline before
installing application dependencies. No application-specific configuration is performed 
at this point. 

Let's apply this to our current Ubuntu server:
```bash
# Update our package list & install latest update
sudo apt update && sudo apt full-upgrade -y

# Install Proxmox guest agent and SSH server
sudo apt install -y qemu-guest-agent openssh-server

# Enable and start the installed services
sudo systemctl enable --now qemu-guest-agent ssh
```
### 1.3 Install required packages (Java, etc.)
The required runtime packages were installed to support execution of the
Spring Boot application. This included installing a compatible Java runtime
environment and any supporting tools required to build or run the
application.

Package versions were selected to align with what is commonly found in
legacy Spring Boot deployments.

Now we need to install the required runtime packages to support execution 
of the Spring Boot application. This includes installing a compatible Java runtime
environment and any supporting tools required to build or run the application. 
The package versions selected in this lab were selected to align with what is 
commonly found in legacy Spring Boot deployments.

Spring Boot 3.x requires Java 17 or newer. Let's go with Java 17 as it is a 
Long-Term Support (LTS) version, widely used in enterprise environments, and fully
supported by modern Spring Boot versions. This ensures a safe and realistic setup 
for a legacy-to-modern migration lab. We need both build and run, so let's download
`openjdk-17-jdk`. OpenJDK is an open-source implementation of Java and installing 
this Java Development Kit gives us everything needed to build and run Java applications. 

This is much more practical as it provides the following:
- Java compiler (`javac`)
- Java runtime (`java`)
- Standard Java libraries
- Tools used by build systems like Maven
This is why our Spring Boot application will work with it. All we need to do is install one package:
```bash
sudo apt install openjdk-17-jdk -y
java --version
```
After we confirm the Java version matches what we want, we can move onto the next phase.
### 1.4 Deploy / run the Spring Boot application
This section is going to focus on building and deploying our Spring Boot application directly 
on the host as a standalone JAR file. The application was initially started manually using the 
Java runtime, showcasing a typical legacy deployment approach.

For the sake of improving reliability, the application will then be configured to run as
a `systemd` service. This allows it to start automatically on boot and be managed using
standard Linux service controls.
#### 1.4.1 Build the Application
We're going to generate the Spring Boot application using Spring Initializer (https://start.spring.io) 
	and transfer it to the legacy VM as a compressed source archive using `scp`. We can then build the
application directly on the legacy host using Maven.

This reflects a common legacy deployment pattern where source code, build tooling, and runtime 
execution all exist on the same server. Updates require manually transferring the source code, 
rebuilding the application, and restarting the process. The lack of separation between build and 
runtime responsibilities highlights the need for Continuous Integration (CI), which is able to separate 
artifact creation from application execution and reduce manual intervention.

For our lab, we're going to be using Maven as our build tool. Maven was chosen because of its 
widespread use in Java-based enterprise applications, predictable dependency management, and
compatibility with CI systems such as Jenkins, which we'll introduce later in the migration. By opting
for Maven now, we ensure alignment between the legacy deployment model and our future Jenkins
CI pipeline, where we known Maven will also be used to produce build outputs used for containerization.

Let's include the following dependencies:
- **Spring Web:** Provides REST API capabilities and foundational web-oriented integration features
- **Spring Boot Actuator:** Exposes health and metrics endpoints for basic observability 
- **Validation:** Enables request validation and input constraints

We can either generate the application using our CLI or the web UI. If we opt for using the CLI method, the 

Instead of using the web interface (Spring Initializr: https://start.spring.io), we can use `curl` to call its 
public API directly and generate the project in our CLI environment:
```bash
curl https://start.spring.io/starter.zip \
  -d type=maven-project \
  -d language=java \
  -d bootVersion=3.2.1 \
  -d baseDir=legacy-app \
  -d groupId=com.lab.legacy \
  -d artifactId=legacy-service \
  -d name=legacy-service \
  -d packageName=com.lab.legacy.service \
  -d dependencies=web,actuator,validation \
  -o legacy-service.zip
```

After unzipping the JAR artifact (`legacy-service.zip`) in this case by running `unzip legacy-service.zip`, 
we can `cd` into the unzipped artifact's main directory. If we open `pom.xml` in the main directory, we can
notice all the dependencies included and verify the dependencies we selected are included.

Now we need to add a minimal REST controller so the application does something observable.
#### 1.4.2 Create the Controller
Now we can add a lightweight REST controller to provide a simple HTTP endpoint for functional verification. 
Having this endpoint serves the following purposes:
- Validate the app works, by confirming the Spring Boot application is running and reachable over HTTP
- Make the Container/Kubernetes behavior meaningful by providing a stable request/response that can 
  be reused later when validating containerized and Kubernetes deployments.
Without the controller, we'd be deploying a dead service.

If navigate to our `service` directory, let's first create a `controller` directory where we can create a REST 
named `HelloController.java`. If you have difficulty finding the adequate `service` directory, installing and 
using the `tree` command from the apps main directory helps visualize the apps structure and where different 
components live. This controller will return a JSON response when testing the `/hello` endpoint because:
- Docker tests can parse JSON
- Kubernetes health probes and checks can hit it
- More metadata fields can be added later
- It more closely resembles a real service response
Here's what our `HelloController` file will look like:
```java
package com.lab.legacy.legacy.service.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
public class HelloController {

        @GetMapping("/hello")
        public Map<String, String> hello() {
                return Map.of(
                        "status", "ok",
                        "service", "legacy-service"
                );
        }
```
Luckily, Spring Boot automatically serializes the `Map` to Jason using Jackson, meaning that the framework 
easily converts Java `Map` objects to JSON format when returning it from the controller method. This is great
as it means avoiding having to write explicit conversion code. This endpoint will serve as a consistent validation
target throughout the migration process. It allows us to verify functionality in the legacy VM, within a Docker 
container, and later inside Kubernetes without modifying the logic of the base application.
#### 1.4.3 Initial Run

#### 1.4.4 Systemd Implementation

### 1.5 Validate the application is running

## 2. External Dependency: PostgreSQL
### 2.1 Create the PostgreSQL host (or service)
### 2.2 Install PostgreSQL
### 2.3 Create database + user
### 2.4 Configure network access (listen_addresses, firewall, pg_hba.conf)
### 2.5 Configure the Spring Boot connection settings
### 2.6 Validate connectivity end-to-end


---
# Migration Strategy

**Status:** *Completed. Missing polished documentation*

---
# CI & Platform Implementation

**Status:** *Completed. Missing polished documentation*

---
# Kubernetes Implementation

**Status:** *Completed. Missing polished documentation*

---
# External Dependency Integration (PostgreSQL / RDS)

**Status:** *In Progress*

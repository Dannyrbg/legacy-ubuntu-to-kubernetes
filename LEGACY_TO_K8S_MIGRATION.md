
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
### 1.4 Build the Application
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
### 1.5 Create the Controller
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
### 1.6 Initial Run
With the controller in place, we can run the application directly on the legacy host VM to validate that it starts
successfully and serves HTTP requests. Navigate to the project's root directory, and first make sure we have the
right permissions to run the `./mvnw` bash script to build the application from source and produce a deployable JAR:
```bash
ls -l mvnw
# Make script executable
chmod +x mvnw
```
Now, let's build the application using Maven:
```bash
cd ~/legacy-service
./mvnw clean package
```
Running this script runs the Maven Wrapper included with the Spring Boot project. `./mvnw` runs the wrapper 
script in the current directory, ensuring the correct Maven version is used and doesn't require a system-wide 
installation of Maven. `clean` deletes the `target/` directory, removing old compiled files and artifacts to ensure
a fresh build. `package` compiles the Java source code, runs tests (if configured), and packages the application 
into a JAR file.

This produces an executable JAR file in the `target/` directory. Inside the `target/` directory, we'll see something
like `legacy-service-0.0.1-SNAPSHOT.jar`; this is our executable Spring Boot artifact.

Next, let's start the application manually using the Java runtime:
```bash
java -jar target/*.jar
```
Running `java` runs the Java Virtual Machine, while `-jar` tells Java to execute the JAR as an application. Spring
Boot JAR files are self-contained, including an embedded Tomcat server and all required application dependencies.
We use the shell wildcard in `target/*.jar` that expands to: `target/legacy-service-0.0.1-SNAPSHOT.jar`.

There are four key things that occur when we run this command. Spring Boot:
1. Starts the embedded Tomcat server
2. Binds to port 8080 (default)
3. Loads our controller
4. Serves HTTP requests
You should see something like this:
```bash
Tomcat started on port(s): 8080
Started LegacyServiceApplication
```
A potential pitfall to avoid is using `sudo` when executing the starting application command. Running the command
as root increases security risk. It isn't needed for port 8080 and is just general malpractice for application services. 
When we implement `systemd` in the next section, we'll be running the application under a dedicated service user 
and not root.

After the application is started and it launches the embedded web server on port 8080, we can open a separate 
terminal session to test the endpoint using `curl`.

Running:
```bash
curl http://localhost:8080/hello
```
Should output:
```JSON
{"status":"ok","service":"legacy-service"}
```

If you can't open a separate terminal, we can alternatively start the application in the background by adding `&` 
to the end of the command:
```bash
java -jar target/*.jar &
```

Up until this point, we have a working legacy workload to migrate. The application and internal dependencies piece
is basically done. Before continuing, let's make a few small configuration adjustments to better align the application 
with production-style operations. Specifically, we will explicitly define the server port and enable health endpoints
for observability.

In `src/main/resources/application.properties`, add:
```properties
server.port=8080
management.endpoints.web.exposure.include=health,info
management.endpoint.health.show-details=always
management.endpoints.health.probes.enabled=true
management.health.jms.enabled=false
```
These properties ensure:
- The application explicitly listens on port `8080`
- The Actuator health endpoint is exposed over HTTP
- Liveness and readiness probe endpoints are enabled
- Unused health indicators (such as JMS) are disabled to avoid 
  unnecessary noise or false reads from the other health probes.

This will give us:
- `/actuator/health`
- `/actuator/health/liveness`
- `/actuator/health/readiness`
Adding these Actuator endpoints is important to expose liveness and readiness endpoints so the runtime platform 
can determine container health. These endpoints will later be used by Docker and Kubernetes to verify container 
health and readiness without modifying the application code.

For quick validation, we can run:
```bash
curl http://localhost:8080/actuator/health
curl http://localhost:8080/actuator/health/liveness
curl http://localhost:8080/actuator/health/readiness
```
### 1.7 Systemd Implementation
Running the application manually using `java -jar` requires a persistent terminal session and it doesn't survive 
system reboots. To simulate a more realistic legacy operations model, let's convert the application into a 
`systemd`-managed service.

This allows the application to:
- Start automatically
- Restart on failure
- Be managed using standard Linux service controls
- Produce centralized logs via `journalctl`

First, we need to create a non-login system user and a stable installation directory. This separates application
execution from administrative accounts and resembles common production practices.
```bash
sudo useradd -r -s /usr/sbin/nologin legacyservice || true
sudo mkdir -p /opt/legacy-service
sudo chown -R legacyservice:legacyservice /opt/legacy-service
```
This is important because we avoid running the application as root, create a stable path independent of the build
directory, and provide a cleaner operational separation.

Now we can build the application from the project root directory and copy the resulting JAR to the stable 
installation directory:
```bash
./mvnw package
sudo cp target/*.jar /opt/legacy-service/app.jar
sudo chown -R legacyservice:legacyservice /opt/legacy-service/app.jar
```
By using a fixed name like `app.jar`, we simplify service configurations and updates.

Now we create the `systemd` unit file:
```bash
nano /etc/systemd/system/legacy-service.service
```
```ini
[Unit]
Description=Legacy Spring Boot Service
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=legacyservice
Group=legacyservice
WorkingDirectory=/opt/legacy-service
ExecStart=/usr/bin/java -jar /opt/legacy-service/app.jar
Restart=on-failure
RestartSec=3
SuccessExitStatus=143

# Optional hardening (safe defaults)
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=full
ProtectHome=true

[Install]
WantedBy=multi-user.target
```
This configuration ensures the service runs under a dedicated user, restarts on failure, and integrates with
the system's boot process.

Let's enable and start the service:
```bash
sudo systemctl daemon-reload
sudo systemctl start legacy-service
sudo systemctl enable legacy-service
```
Validate service operations:
```bash
curl http://localhost:8080/hello
curl http://localhost:8080/actuator/health
sudo journalctl -u legacy-service -f
```
At this stage, the application behaves like a traditional Linux-managed service and survives reboots.

If we needed to update the application, in the cases where code changes are made, we need to rebuild 
and redeploy the artifact, then restart the service:
```bash
./mvnw package
sudo cp target/*.jar /opt/legacy-service/app.jar
sudo chown legacyservice:legacyservice /opt/legacy-service/app.jar
sudo systemctl restart legacy-service
```
This process highlights the manual nature of legacy deployments: artifact replacement followed by a 
service restart.

While this approach improves reliability compared to manual execution, build, deployment, and runtime, 
responsibilities remain tightly dependent to the host. This limitation motivates the introduction of containerization
and CI in the next phase of migration. 

**Troubleshooting: Port Conflicts**
If the service fails to start and logs indicate that port `8080` is already in use, another process is bound to
the same port.
We can resolve this issue as follows:
```bash
sudo systemctl stop legacy-service
sudo lsof -i :8080
sudo kill -9 <PID>
sudo systemctl start legacy-service
sudo systemctl status legacy-service --no-pager
```
## 2. External Dependency: PostgreSQL
The legacy application currently operates without a persistent data store. To better simulate a realistic enterprise workload, we will introduce PostgreSQL as an external database dependency. This change requires both application-level updates and database environment setup.
### 2.1 Update Application Dependencies
In order to support database connectivity and persistence, the following dependencies are added to the project's `pom.xml` file:
```xml
<!-- Data + ORM -->
<dependency>
		<groupId>org.springframework.boot</groupId>
		<artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>

<!-- PostgreSQL JDBC driver -->
<dependency>
		<groupId>org.postgresql</groupId>
		<artifactId>postgresql</artifactId>
		<scope>runtime</scope>
</dependency>

<!-- Database Migrations -->
<dependency>
		<groupId>org.flywaydb</groupId>
		<artifactId>flyway-core</artifactId>
</dependency>

<!-- In-memory DB for tests -->
<dependency>
		<groupId>com.h2database</groupId>
		<artifactId>h2</artifactId>
		<scope>test</scope>
</dependency>
```
We added these dependencies for the following reasons:
- **Spring Data JPA:** Enables ORM-based (Object Relational Mapping) persistence.
- **PostgreSQL driver:** Allows runtime connectivity to PostgreSQL.
- **Flyway:** Manages database migrations schema
- **H2 (test scope):** Allows unit tests to run without requiring a live PostgreSQL instance.
#### Handling Spring Boot Auto-Configuration
After adding the JPA and Flyway dependencies, Spring Boot will attempt to auto-configure a `DataSource` during the application's startup. Since a database has not yet been provisioned, startup will fail with an error similar to:
	`Failure to determine a suitable driver class`
This occurs because:
- JPA triggers `DataSource` auto-configuration.
- Flyway attempts to initialize database migrations.
- No `spring.datasource.*` properties have been defined yet.
Either way, the default `contextLoads()` test now requires a database configuration, and one hasn't been provided yet.

While the PostgreSQL infrastructure is being provisioned, let's temporarily disable auto-configuration until PostgreSQL is provisioned. Let's add the following to our `application.properties` file in our legacy project:
```properties
spring.autoconfigure.exclude=\
org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,\
org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,\
org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration
```
This allows the application to continue running while infrastructure configuration proceeds. Once PostgreSQL is available, these exclusions will be removed and proper connection settings will be configured.
After adding the exclusions, we can rebuild and redeploy the application:
```bash
./mvnw package
sudo cp target/*.jar /opt/legacy-service/app.jar
sudo systemctl restart legacy-service
```
### 2.2 Create the PostgreSQL Host

To simulate a realistic external dependency boundary, PostgreSQL is going to be hosted on a dedicated Virtual Machine separate from the legacy application host.

Let's provision a new Ubuntu 22.04 server VM in our local Proxmox environment with:
- 8 GB RAM
- ~50 GB storage
- Private network connectivity within the lab subnet
Adding this separation reflects traditional multi-tier application deployments, where application services and stateful dependencies reside on separate hosts.

**Configure Static Network Addressing**

To ensure stable connectivity between the legacy application and its external dependency, let's assign a static IP address to the PostgreSQL host.

Relying on DHCP for stateful infrastructure can introduce unnecessary instability if addresses change. Implementing static addressing in this instance more closely mirrors common production environments where application servers reference database endpoints via fixed network identifiers (i.e. fixed IP addresses).

The network configuration was updated via Netplan, which on our server exists in `/etc/netplan/50-cloud-init.yaml`:
```yaml
network:
    ethernets:
        ens18:
            dhcp4: no
            addresses:
              - 192.168.10.50/24 # Assign whichever static IP you wish to use
            routes:
              - to: default
                via: 192.168.10.1
            nameservers:
              addresses:
                - 1.1.1.1
                - 8.8.8.8
    version: 2
```
Now, let's apply the configuration:
```bash
sudo netplan apply
```
Now let's validate and verify it went into effect and is working properly:
```bash
ip a show ens18
ip route
ping -c 3 192.168.10.1
ping -c 3 8.8.8.8
```

Since the VM was provisioned using `cloud-init`, the default Netplan file may be regenerated on reboot. In order to prevent network configuration from being overwritten, we need to disable `cloud-init` network management.

In `/etc/cloud/cloud.cfg.d/99-disable-network-config.cfg`, add:
	`network: {config: disabled}`

Reboot and verify that the static configuration persists.

Before installing application dependencies, let's apply basic host hardening. Let's install the Uncomplicated Firewall (UFW) and configure it to allow secure administrative access via SSH:
```bash
sudo apt install -y ufw
sudo ufw allow OpenSSH
sudo ufw enable
sudo ufw status
```
### 2.3 Install PostgreSQL

PostgreSQL is installed on the dedicated dependency VM using the official Ubuntu package repositories. Ubuntu 22.04 ships with PostgreSQL 14 by default, which provides modern authentication support (SCRAM-SHA-256) and long-term stability. After updating the package index (`sudo apt update`), let's install PostgreSQL and Contrib packages:
```bash
sudo apt install -y postgresql postgresql-contrib
```
Here, `postgresql` is the core database server, while `postgresql-contrib` includes additional utilities and extensions.
We can verify installation and version by running:
```bash
psql --version
```
We should see it output version 14.x. This confirms installation.

Ubuntu installs PostgreSQL as a `systemd`-managed service. Let's configure `postgresql` to start immediately and on boot, and then verify the service status:
```bash
sudo systemctl enable --now postgresql
sudo systemctl status postgresql --no-pager
```
We should expect to see this as the service status: `Active: active (running)`

Before we enable remote access, let's confirm default behavior:
```bash
ss -lntp | grep 5432
```
We should see: `127.0.0.1:5432`

By default, PostgreSQL only listens on the loopback interface (`127.0.0.1`), preventing remote access until explicitly configured. Now we've confirmed that PostgreSQL is running and is currently restricted to local connections.

Ubuntu manages PostgreSQL instances as "clusters." Ubuntu initializes a default PostgreSQL cluster during installation. Additional databases are created within this cluster.
We can check by running:
```bash
sudo -u postgres psql -c "\l"
```
This lists databases. We should see the following default databases:
- `postgres`
- `template0`
- `template1`

The data directory for the default cluster lives in: `/var/lib/postgresql/14/main`
### 2.4 Create Database & User

Rather than using the default `postgres` superuser account, let's create a dedicated database and role for the application. This follows the principle of least privilege and isolates application access from administrative control.

The `postgres` system account owns the PostgreSQL service and is used for database administrative tasks. Let's switch to the `postgres` user:
```bash
sudo -i -u postgres
```
We can now create an application role:
```bash
createuser legacyuser --pwprompt
```
This creates a new database role named `legacyuser`. The `--pwprompt` flag ensures a password is set during creation.
This sets us up to create the application database:
```bash
createdb legacydb -O legacyuser
```
The database `legacydb` is created and ownership is assigned to `legacyuser`, ensuring the application user has full control over its own database while remaining isolated from other databases. If we want to quickly verify that `legacyuser` and `legacydb` exist and ownership is correct, we can perform the following:
```ruby
psql
\du
\l
# quit
\q
```
### 2.5 Configure Network Access (listen_addresses, firewall, pg_hba.conf)

As we already previously addressed, by default, PostgreSQL on Ubuntu is configured for local-only access. Since the legacy application runs on a separate VM, PostgreSQL must be explicitly configured to accept and authorize remote TCP connections.

PostgreSQL access control happens in two layers:
1. **Network-level Listening (**`listen_addresses`**):** whether PostgreSQL accepts TCP connections on an interface at all.
2. **Client Authentication (**`pg_hba.conf`**):** which users/databases are allowed to connect, from where, and using what authentication method.

#### Enable Network Listening
Let's begin by enabling network listening. We need to edit the PostgreSQL configuration file: `/etc/postgresql/14/main/postgresql.conf`
Update (or uncomment) the following setting:
	`listen_addresses = '*'`
This allows PostgreSQL to listen on all network interfaces. A more restrictive option would be to bind only to the VM's private IP (e.g. `192.168.10.50`), but let's use `*` for this lab for simplicity and to avoid issues if the interface address changes.
After this is done, let's restart PostgreSQL and verify that it is listening on port 5432:
```bash
sudo systemctl restart postgresql
ss -lntp | grep 5432
```
The expected output should match `0.0.0.0:5432` (IPv4), which confirms PostgreSQL is accepting TCP connections.
If we wish to additionally confirm the interface name, subnet, and default gateway before applying authentication rules, we can. It's optional, but good for clarity:
```bash
ip a show ens18
ip route | head
```

#### Configure Client Authentication
At this point PostgreSQL may accept TCP connections, but clients will still be rejected unless they match an authentication rule in `pg_hba.conf`. In `/etc/postgresql/14/main/pg_hba.conf`, add the following rule near the bottom, above any generic `host all all ...` rules:
```conf
host    legacydb     legacyuser     192.168.10.0/24     scram-sha-256
```
This rule allows the application user `legacyuser` to connect to the database `legacydb` from hosts in the `192.168.10.0/24` subnet using SCRAM-SHA-256 password authentication.

Additionally, to ensure new passwords are stored using SCRAM hashes, confirm the following setting in `postgresql.conf`:
	`password_encryption = scram-sha-256`
Now we need to restart PostgreSQL to apply changes:
```bash
sudo systemctl restart postgresql
```

#### Open Firewall Access
Finally, we need to allow PostgreSQL traffic through the host firewall:
```bash
# Better option
sudo ufw allow from 192.168.10.0/24 to any port 5432 proto tcp

# Simple, but less secure
sudo ufw allow 5432/tcp
```
Then verify rules:
```bash
sudo ufw status
```

With listening, authentication rules, and firewall access configured, PostgreSQL can now be consumed as a true external dependency by the legacy application over the network.
### 2.6 Configure the Spring Boot Connection Settings

Before integrating PostgreSQL with the application, we should validate connectivity directly from the legacy application VM using the PostgreSQL client tools. Let's install the PostgreSQL Client on the legacy application VM:
```bash
sudo apt update
sudo apt install postgresql-client -y
```
We can then test the remote connection:
```bash
# Replace host IP with your PostgreSQL VM's IP
psql "host=192.168.10.50 dbname=legacydb user=legacyuser" -c "select now();"
```
After entering the password, a timestamp should be returned.
If it executes successfully, we can confidently confirm:
- Network connectivity between VMs
- Firewall configuration
- `listen_addresses` configuration
- `pg_hba.conf` authentication rules
- Valid database credentials
At this stage, PostgreSQL is functioning as an external dependency.

With infrastructure validated, we can now configure the application to use PostgreSQL as its primary data source.
We need to edit our `application.properties` file in our legacy project. Let's add the following to the file:
```properties
spring.datasource.url=jdbc:postgresql://192.168.10.50:5432/legacydb
spring.datasource.username=legacyuser
spring.datasource.password={legacyuser_password}
```
Earlier, we disabled database auto-configuration to allow incremental setup. Now that PostgreSQL is available, let's remove:
	`spring.autoconfigure.exclude=...`
This allows Spring Boot to create:
- DataSource
- EntityManager
- TransactionManager
- Flyway migrations (if configured)

### 2.7 Implement Minimal Persistence Feature

To validate that PostgreSQL is not only reachable but fully integrated into the application runtime, let's implement a minimal database-backend feature.

This feature will verify:
- JDBC connectivity
- Spring Data JPA configuration
- Entity mapping
- Transaction management
- Write and read operations

Rather than introducing complex domain logic, adding a simple persistence model to confirm that the application can create and retrieve records from the database is sufficient.
#### Entity Layer
Let's first create the entity layer. In our project's `service` directory, create a new directory called `model` and create a new file to it called `PingRecord.java`. Add the following to the file:
```java
package com.lab.legacy.legacy.service.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
public class PingRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public Instant getCreatedAt() { return createdAt; }
}
```
The `PingRecord` entity represents a minimal table with:
- A generated primary key
- A timestamp field initialized at creation

The `GenerationType.IDENTITY` strategy delegates ID generation to the database, confirming that PostgreSQL is actively participating in the persistence lifecycle.
#### Repository Layer
Now let's add the repository layer. Create a new directory within the same `service` directory called `repo`. Create a file called `PingRecordRepository.java` and add the following to it:
```java
package com.lab.legacy.legacy.service.repo;

import com.lab.legacy.legacy.service.model.PingRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PingRecordRepository extends JpaRepository<PingRecord, Long> {}
```
The repository extends `JpaRepository`, providing:
- Save operations
- Count queries
- CRUD behavior
- Transactional boundaries managed by Spring

Spring Boot automatically scans for JPA repositories within the same package hierarchy as the main application class. Because the repository resides under the root package, no additional configuration should be required. If it isn't detecting our repository, repository scanning can be explicitly enabled to ensure detection of the JPA repository package. In our `service/LegacyServiceApplication.java` file, add the line highlighted:
```java
...
@SpringBootApplication
-> @EnableJpaRepositories(basePackages = "com.lab.legacy.legacy.service.repo") <-
public class LegacyServiceApplication {
...
```
#### Controller Layer
Lastly, let's add the Controller Layer. In our `controller` directory, we can create a file called `DbPingController.java` and add the following to it:
```java
package com.lab.legacy.legacy.service.controller;

import com.lab.legacy.legacy.service.model.PingRecord;
import com.lab.legacy.legacy.service.repo.PingRecordRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class DbPingController {

        private final PingRecordRepository repo;

        public DbPingController(PingRecordRepository repo) {
                this.repo = repo;
        }

        @GetMapping("/db/ping")
        public Map<String, Object> pingDb() {
                PingRecord saved = repo.save(new PingRecord());
                long count = repo.count();
                return Map.of(
                        "savedId", saved.getId(),
                        "totalRows", count
                );
        }
}
```
The `/db/ping` endpoint performs two operations:
1. Persists a new `PingRecord` entity.
2. Queries the total number of rows in the table.

Each request results in:
- A database insert
- A database count query
- A returned JSON payload confirming both operations

A successful response confirms:
- The application can establish a JDBC connection.
- Authentication credentials are valid.
- The schema exists or is being generated correctly.
- Hibernate can map entities to database tables.
- Transactions are committed successfully.
- The database is reachable over the network.

An successful response should match:
```JSON
{
  "savedId": 5,
  "totalRows": 5
}
```

If `totalRows` increases with each request, this confirms persistent state across requests and verifies PostgreSQL is functioning as a durable, external data store.

### 2.8 Validate connectivity end-to-end

Let's rebuild and redeploy the application:
```bash
./mvnw package
sudo cp target/*.jar /opt/legacy-service/app.jar
sudo systemctl restart legacy-service
```
Test:
```bash
curl http://localhost:8080/db/ping
```
We should notice the following if it executes successfully:
- `savedID` increments
- `totalRows` increases with each call

If no issues arise, we can confirm the following:
- Network-level connectivity
- Application-level DB integration
- ORM and JPA functionality
- Successful write and read cycle
#### Improve Local Security 
Storing database credentials in plaintext reflects traditional legacy deployments. However, credential handling evolves across deployment models:

| Stage      | How password is handled              |
| ---------- | ------------------------------------ |
| Legacy VM  | `application.properties` (plaintext) |
| systemd    | ENV vars                             |
| Docker     | ENV vars                             |
| Kubernetes | Secretes                             |
| Cloud      | External Secret Manager              |

Since at the moment we're storing our `spring.datasource.password` in `application.properties` in plaintext, we create several risks. Let's begin by restricting file permissions to protect secrets at rest on disk:
```bash
sudo chown legacyservice:legacyservice src/main/resources/application.properties
sudo chmod 600 src/main/resources/application.properties
```
While this is a defensive approach, it isn't a modern approach and doesn't scale across environments. Instead of storing the password directly in the file, let's use environment variables. Spring Boot automatically resolve `${...}` placeholders from:
- Environment variables
- JVM system properties
- OS-level environment

Now let's have `systemd` set the environment variables when the process starts, where the app reads it automatically.
In our unit file, add:
```ini
Environment=DB_PASSWORD={legacyuser_password}
```
Then reload:
```bash
sudo systemctl daemon-reload
sudo systemctl restart legacy-service
```

This is overall a better approach and are complementary steps that secure the application. Here's what improved:
- The password is not stored in the app config file.
- It can differ per environment (dev, staging, prod).
- It can be injected at runtime.
- The application code does not change.
---
# Modernization Implementation

## Jenkins VM

To eliminate manual artifact builds and enable repeatable, automated container image creation, we're going to introduce a dedicated Continuous Integration (CI) platform using Jenkins.

Jenkins is responsible for:
- Building the Spring Boot application
- Producing Docker container images
- Publishing versioned images to Amazon ECR

Let's provision a dedicated Ubuntu 22.04 Server virtual machine within our Proxmox environment to host Jenkins. By separating Jenkins onto its own VM, it isolates build workloads from application runtime infrastructure, which reflects common enterprise CI architecture.

Let's begin by preparing our system:
```bash
sudo apt update && sudo apt full-upgrade -y
sudo apt install ca-certificates curl gnupg lsb-release git unzip jq -y
```
These tools support:
- Repository key management
- Secure package installation
- Git-based source control
- JSON processing

Now we need to install Jenkins and our Java requirements:
```bash
# Add Jenkins repository key
curl -fsSL https://pkg.jenkins.io/debian-stable/jenkins.io-2023.key \
  | sudo tee /usr/share/keyrings/jenkins-keyring.asc > /dev/null

# Add Jenkins repository
echo "deb [signed-by=/usr/share/keyrings/jenkins-keyring.asc] https://pkg.jenkins.io/debian-stable binary/" \
  | sudo tee /etc/apt/sources.list.d/jenkins.list > /dev/null

# Java requirements (Java 21 - Jenkins needs it)
sudo apt install fontconfig openjdk-21-jre -y
```
Install and start Jenkins:
```bash
sudo apt install jenkins -y
sudo systemctl enable --now jenkins
sudo systemctl status jenkins --no-pager
```
We can verify Jenkins is listening by runnnig:
```bash
ss -lntp | grep 8080
```

Let's use Uncomplicated Firewall (`ufw`) to expose Jenkins on port 8080:
```bash
sudo ufw allow 8080/tcp
sudo ufw enable
sudo ufw status
```

We can now navigate to the Jenkins web UI and configure it from there.
Let's navigate to `http://<jenkins-vm-ip>:8080`
We can find our initial admin password by opening the `initialAdminPassword` file in `/var/lib/jenkins/secrets/initialAdminPassword`.

For the initial setup, select:
- Install Suggested Plugins
- Create administrative user
### Docker

Jenkins must be able to build Docker images locally before pushing them to Amazon ECR. Let's install the Docker Engine:
```bash
# Repository setup. Add Docker repo key
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
  | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg

# Add Docker repo
echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
  https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

# Install Docker
sudo apt update
sudo apt install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin -y

# Enable, start, and verify Docker
sudo systemctl enable --now docker
docker --version
```

By default, the Jenkins system user does not have permission to access the Docker daemon socket. Therefore, let's add the Jenkins user to the `docker` group using `usermod`:
```bash
sudo usermod -aG docker jenkins
sudo usermod -aG docker $USER
sudo systemctl restart jenkins
```
Let's confirm the Jenkins user can access Docker:
```bash
# Validation
sudo -u jenkins docker ps
```
If the output matches the an empty list like the following:
`CONTAINER ID     IMAGE     COMMAND     CREATED     STATUS     PORTS      NAMES`
We've confirmed then that:
- Jenkins can access Docker
- Docker socket permissions are correct
- No containers are currently running

**Install Maven**
Our next step is installing Maven. Maven is required for building the Spring Boot application within Jenkins. Although the project includes a Maven Wrapper (`mvnw`), installing Maven ensures consistent availability across pipeline stages.
```bash
sudo apt install maven -y
mvn --version
```

**Install Required Jenkins Plugins**
To support the container build and AWS publishing workflow, we need to install the following plugins in Jenkins (via web UI):
- Docker Pipeline
- AWS Credentials
Restart Jenkins when prompted

These plugins matter because:
- Docker Pipeline enables `docker.build()` and container operations inside Jenkins pipelines.
- AWS Credentials allows secure storage of IAM access keys for pushing images to ECR.
These plugins enable the following workflow:
`Spring Boot -> Docker Image -> Amazon ECR`

---
## ECR & IAM

### ECR

With the CI established, the next architectural component we're going to introduce is a container registry. Amazon Elastic Container Registry (ECR) serves as the artifact boundary between the build system (Jenkins) and the runtime platform (Kubernetes).

**Why Introduce a Container Registry?**  
Up until this point, builds produced artifacts that were:
- Stored locally
- Manually transferred
- Tied to specific hosts
That model doesn't scale. By introducing ECR, it's able to provide:
- Centralized image storage
- Versioned artifacts
- Immutable deployment units
- Separation between build and runtime environments
- A pull-based deployment model for Kubernetes
The main point here is, the container registry decouples artifact production from artifact consumption.

**Repository Creation**  
Let's create an ECR repository with the following configuration:
- Visibility: Private
- Repository name: `springboot-legacy-app`
- Region: `us-east-1`
- Image tag mutability: Mutable
- Encryption: AES-256 (default)

The repository is private to prevent public access to application images. Container images often contain application binaries and internal configuration that should not be publicly accessible. Furthermore, the repository region must align with the Kubernetes cluster region to avoid cross-region image pulls, which introduce latency and complexity.

**Repository URI**  
After creation, AWS generates a repository URI:
`<ACCOUNT_ID>.dkr.ecr.us-east-1.amazonaws.com/springboot-legacy-app`
This URI becomes:
- The Docker image tag prefix
- The reference used in our Jenkins pipeline
- The image reference in Kubernetes manifests
The repository URI becomes the official artifact reference for all deployment stages.

**Image Tag Strategy**  
Images will be tagged with version identifiers (e.g. build number) to allow deterministic deployments. Tag mutability was enabled for simplicity in our lab environment. Typically, in production systems, immutable tag policies are preferred to prevent accidental overwrites of released artifacts.

**Encryption**  
ECR images are encrypted at rest using AES-256 by default. This provides baseline protection for stored container layers.

**Updated Architecture**  
At this stage, the architecture evolves from:
`Legacy VM -> Local Artifact -> Manual Deployment`
to:
`Source Code -> Jenkins -> ECR -> Runtime Platform (EKS)`

With ECR in place, the build system can now produce portable, versioned container artifacts that are independent of any specific host. The next step is granting Jenkins narrowly scoped permissions to authenticate and push images to this repository.
### IAM

With the container registry established, the next step is defining a secure identity boundary between the on-premises CI system and AWS. Rather than using human credentials or broad administrative access, let's create a dedicated machine identity specifically for Jenkins.

Jenkins runs outside AWS (on a Proxmox VM), so it cannot assume an IAM role. Therefore, a dedicated IAM user is required to authenticate programmatically using access keys.
This identity:
- Represents the CI system only
- Is isolated from human users
- Has narrowly scoped permissions
- Can be revoked independently

**Create Machine User: `jenkins-ecr`**  
From the AWS console go to *IAM -> Users -> Create User*
User Details:
- Username: *jenkins-ecr*
- Choose: *Attach Policies Directly*
	- We don't want to pick any existing AWS managed policies yet. We're going to create our own next.
After user creation, we're going to explicitly create the access key. In order to create the access key for Jenkins:
1. Click the newly created user *jenkins-ecr*
2. Go to the *Security Credentials* tab
3. Scroll to *Access Keys*
4. Click *Create access key*
5. For the "Use case:" option, choose *Application running outside AWS*
6. Then click next and create access key.
The secret access key is only shown once during creation and must be stored securely. It should never be committed to source control or stored in plaintext.

Rather than attaching broad AWS managed policies (e.g., AmazonEC2ContainerRegistryFullAccess), let's define a custom policy to allow only the minimum actions required for Docker image pushes.

Up until this point, the user has no permissions yet and Jenkins cannot access ECR yet. This is done intentionally. We can now add the least-privilege policy next.

*Policy Objectives*
Jenkins must be able to:
- Authenticate to ECR
- Upload image layers to only our specific repo
- Publish image manifests

It must NOT be able to:
- Delete repositories
- Modify other repositories
- Access EKS
- Modify IAM
- Access unrelated AWS services

To create this policy, open IAM Policies: *AWS Console -> IAM -> Policies (left sidebar)* and select *Create policy*
Click the JSON tab and delete all its contents. Write this into the JSON file:
```JSON
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": "ecr:GetAuthorizationToken",
      "Resource": "*"
    },
    {
      "Effect": "Allow",
      "Action": [
        "ecr:BatchCheckLayerAvailability",
        "ecr:InitiateLayerUpload",
        "ecr:UploadLayerPart",
        "ecr:CompleteLayerUpload",
        "ecr:PutImage"
      ],
      "Resource": "arn:aws:ecr:us-east-1:<ACCOUNT_ID>:repository/springboot-legacy-app"
    }
  ]
}
```
We set *Resource* to *\** for *GetAuthorizationToken* because AWS requires it since auth tokens are global, not repo-scoped.
After clicking next, we can name the policy and provide a quick description:
- *Name*: JenkinsECRPushPolicy
- *Description:* Allow Jenkins to push Docker images to ECR repository springboot-legacy-app
We can then officially create the policy.

The policy is divided into two statements:
1. `ecr:GetAuthorizationToken`:Required to obtain a temporary authentication token. This must use `"Resource": "*"` because ECR auth tokens are not repository-scoped.
2. *Repository-scoped push actions:* These are restricted to a single repository ARN: springboot-legacy-app.


Now let's attach the policy to our *IAM user*:
1. Go back to *IAM -> Users*
2. Click *jenkins-ecr*
3. Go to the *Permissions* tab
4. Click *Add permissions*
5. Choose *Attach policies directly*
6. Search for: *JenkinsECRPushPolicy*
7. Check the box
8. Click *Add permissions*

We should now see two important things on our user's page:
- *Permissions:* JenkinsECRPushPolicy
- *Access keys:* 1

The architecture now establishes a controlled trust relationship:
```ascii
Jenkins VM (external system)
        ↓
IAM User: jenkins-ecr (least privilege)
        ↓
ECR Repository: springboot-legacy-app
```

### Integrate Credentials into Jenkins

To allow our Jenkins pipeline to authenticate to AWS, the IAM credentials need to be stored securely in the Jenkins credential store.

Once logged in as the admin in Jenkins UI, go to *Manage Jenkins -> Credentials* and click on *Global* highlighted in blue in *Domains* under *System* in *Stores scoped to Jenkins*. Click *Add Credentials*.

Fill in AWS credentials using the following values:
- *Kind:* AWS Credentials
- *Scope:* Global
- *Access Key ID:* paste from `jenkins-ecr`
- *Secret Access Key:* paste from `jenkins-ecr`
- *ID:* aws-ecr
- *Description:* IAM user for Jenkins to push images to ECR (us-east-1)
Click *Create*. Now Jenkins securely stores the credentials.

From the Jenkins VM terminal, we run `aws sts get-caller-identity` to see if IAM is wired correctly. I, however, received the following error:
	`Unable to locate credentials. You can configure credentials by running "aws login".`
The AWS CLI on the Jenkins VM operates independently from the Jenkins credential store. Therefore, CLI-level commands will fail unless credentials are configured for the underlying system user.

Our best fix is configuring the credentials with `sudo -u jenkins aws configure`. It's not limiting, but it is a temporary, VM-local convenience. It's perfectly fine and acceptable for validation and early pipelines, but not the end state. Think of it as a "fall-back credential source." For our lab, it's totally okay since it:
- Proves IAM policy correctness
- Proves ECR access
- Removes ambiguity (“is Jenkins broken or AWS broken?”)
- Lets you run manual validation commands

Let's configure AWS credentials as the `jenkins` user:
Run: `sudo -u jenkins aws configure`
Enter:
- *AWS Access Key ID:* paste from `jenkins-ecr` user
- *AWS Secret Access Key:* paste from `jenkins-ecr` user
- *Default region name:* `us-east-1`
- *Default output format:* `json`

Let's test identity as `jenkins`:
	`sudo -u jenkins aws sts get-caller-identity`
It's expected output should be key-value pairs of `UserId`, `Account`, and `Arn`
Now let's test our ECR login still as `jenkins`:
```bash
sudo -u jenkins aws ecr get-login-password --region us-east-1 | sudo -u jenkins docker login --username AWS \
--password-stdin <ACCOUNT_ID>.dkr.ecr.us-east-1.amazonaws.com
```
The expected output is: `Login Succeeded`

We've validated ECR access using the Jenkins service account before binding credentials in the pipeline.

In production environments, static IAM access keys would ideally be replaced with IAM roles. Static access keys are used here because Jenkins is running outside AWS infrastructure.

---
## Jenkins Pipeline

With Jenkins integrated with ECR, the next step is defining a deterministic, repeatable CI pipeline that transforms source code into a versioned container artifact.

The pipeline implements a three-stage workflow:
1. Build (Maven)
2. Dockerize (container image)
3. Push (Amazon ECR)

At this stage, Kubernetes deployment is intentionally excluded. The objective is to establish a stable artifact production pipeline before introducing orchestration. Since our code exists on the legacy VM, we'll make Jenkins pull the source from the legacy VM over SSH, then build and push to ECR. The best and most effective method to do that would be by having Jenkins use `git` on the legacy VM.

### Application Configuration Sanitation

Before creating and committing the repository, let's make sure we don't have any database credentials configured in plaintext within our project. Let's check `application.properties` in `~/src/main/resources/`.

We see that it includes the PostgreSQL database username and URL in plaintext. Since it's a `systemd` service, we can follow the same steps we did earlier for the password to swap them for environment variables, so that it looks like:
```properties
spring.datasource.url=jdbc:postgresql:${DB_URL}
spring.datasource.username=${DB_USER}
spring.datasource.password=${DB_PASSWORD}
```
If we were to try running `git add .`, which we will do shortly, we'll receive an error like this:
```bash
error: open("src/main/resources/application.properties"): Permission denied
error: unable to index file src/main/resources/application.properties
fatal: adding files failed
```
Git is trying to read the `application.properties` file but the current user (admin in this case) doesn't have read permission on it. This happened because the file was created by the install script. If we check ownership, it'll look like this:
	`-rw------- 1 legacyservice legacyservice 437 Feb  4 01:41 application.properties`
	- This is a very common side-effect of `systemd`-managed applications. Our Spring Boot app was running as `User=legacyservice` in a `systemd` unit. So when the app, or startup script, created or modified the `application.properties` file, Linux correctly set the owner to `legacyservice:legacyservice`.
	
We can fix ownership and ensure it's readable by using `chown` and `chmod`:
```bash
sudo chown admin:admin src/main/resources/application.properties
chmod 644 src/main/resources/application.properties
```
This way:
- Runtime data = owned by service user (`legacyservice`)
- Source code = owned by admin user
Now if we check ownership, we'll see it's fixed and look like this:
	`-rw-r--r-- 1 admin admin 437 Feb  4 01:41 application.properties`
We can continue with using `git` now.

### Source Control Integration (Git)

To enable automated builds, we need to migrate the application source code to a private GitHub repository. This creates a formal boundary between development and CI execution. 

We don't want to build from the legacy VM directly, as building from the legacy host would:
- Preserve tight host coupling
- Prevent version tracking
- Limit CI scalability
- Complicate rollback

Introducing Git gives us:
- Version history
- Branching
- Deterministic pipeline triggers
- Traceable build metadata

**GitHub Repository Setup**  
Let's create a new GitHub repository with these options:
- Repository: `legacy-service`
- Visibility: Private
- Branch: `main`
SSH-based authentication is configured for both:
- Legacy VM (for pushing code)
- Jenkins VM (for pulling code)
This way, we can avoid storing GitHub password or tokens.

**Secure SSH Integration**  
We need generate an SSH key to serve as the legacy VM's cryptographic identity (unless you already have one you can use) so that GitHub knows that the machine is allowed to act as us. We need to run `ssh-keygen` on the legacy VM:
```bash
ssh-keygen -t ed25519 -C "legacy-vm"
```
Press `Enter` for the pop-up prompts for default behavior. Then `cat` the new SSH key in *~/.ssh* and copy the entire line as outputted.
Take that entire line and create a new SSH key in GitHub by navigating to: 
	*GitHub -> Settings -> SSH and GPG Keys -> New SSH key*
Paste the copied output from `ed25519` and save it.

We can test it by running `ssh -T git@github.com` on the legacy-VM. If the output matches: `Hi {username}! You've successfully authenticated...`, then we're golden.

We also need to make Jenkins be able to clone our private repository. On the Jenkins VM, generate an SSH key for the `jenkins` user and copy the entire output after catting it:
```bash
sudo -u jenkins ssh-keygen -t ed25519 -N "" -f /var/lib/jenkins/.ssh/id_ed25519
sudo -u jenkins cat /var/lib/jenkins/.ssh/id_ed25519.pub
```
Add that public key to GitHub as a **Deploy Key**:  
- *GitHub repository* -> *Settings* -> *Deploy keys* -> *Add deploy key*
- Title: Jenkins
- Paste key
- Leave "Allow Write Access" off, since Jenkins only needs to read
This ensures that Jenkins can clone, can't push changes, and has minimal repository privileges.

Then, on the Jenkins VM, trust GitHub host key:
```bash
sudo -u jenkins ssh -o StrictHostKeyChecking=accept-new git@github.com
```
This prevents interactive prompts during pipeline execution.

In the case that it states that the `.../jenkins/.ssh` directory doesn't exist, we can create it with the correct ownership and permissions:
```bash
sudo -u jenkins mkdir -p /var/lib/jenkins/.ssh
sudo chmod 700 /var/lib/jenkins/.ssh
# After creating SSH key
sudo chmod 600 /var/lib/jenkins/.ssh/id_ed25519
sudo chmod 644 /var/lib/jenkins/.ssh/id_ed25519.pub
```

**Initialize Git Repo & Commit**  
On the legacy VM, in the Spring Boot app, initialize Git repo and commit:
```bash
cd /path/to/project
git config --global user.name "{your_GitHub_username}"
git config --global user.email "{your_GitHub_email}"
git init
git add .
git commit -m "{initial_import_description}"
git branch -M main

# Add remote and push
git remote add origin git@github.com:<YOUR_USER>/legacy-service.git
git push -u origin main
```

Now we need to create the `Dockerfile` next to `pom.xml`:
```Dockerfile
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
```
The key thing here is that we're producing an immutable runtime artifact.

Now we can create and add the `Jenkinsfile` to the repo (*build* -> *docker* -> *push ECR*):
```ruby
pipeline {
  agent any

  environment {
    AWS_REGION   = "us-east-1"
    ECR_REGISTRY = "<Account_ID>.dkr.ecr.us-east-1.amazonaws.com"
    ECR_REPO     = "springboot-legacy-app"
    IMAGE_TAG    = "${env.BUILD_NUMBER}"
  }

  stages {
    stage("Checkout") {
      steps { checkout scm }
    }


stage('Build (Maven)') {
  steps {
    sh(label: 'Build with Bash', script: '''
      bash -lc '
        set -euo pipefail
        set -x
        echo "SHELL=$SHELL"
        echo "0=$0"
        ps -p $$ -o comm=

        export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
        export PATH="$JAVA_HOME/bin:$PATH"

        echo "JAVA_HOME=$JAVA_HOME"
        which java
        which javac
        java -version
        javac -version
        mvn -version

        mvn -B -V clean package -DskipTests
      '
    ''')
  }
}


    stage("Docker build") {
      steps {
        sh '''
          set -e
          docker build -t ${ECR_REPO}:${IMAGE_TAG} .
          docker tag ${ECR_REPO}:${IMAGE_TAG} ${ECR_REGISTRY}/${ECR_REPO}:${IMAGE_TAG}
        '''
      }
    }

    stage("Login to ECR") {
      steps {
        sh '''
          set -e
          aws ecr get-login-password --region ${AWS_REGION} \
            | docker login --username AWS --password-stdin ${ECR_REGISTRY}
        '''
      }
    }

    stage("Push to ECR") {
      steps {
        sh '''
          set -e
          docker push ${ECR_REGISTRY}/${ECR_REPO}:${IMAGE_TAG}
        '''
      }
    }
  }
}
```
A declarative Jenkins pipeline was defined in this `Jenkinsfile` to codify the entire build process as code. The different pipeline stages included are:
1. Checkout
2. Maven Build
3. Docker Build
4. ECR Login
5. Push to ECR
For our environment configuration, by using `${BUILD_NUMBER}`, we ensure versioned image tags and avoid reliance on `latest`.

After creating and editing both our `Dockerfile` and `Jenkinsfile`, let's commit it:
```bash
git add Dockerfile Jenkinsfile
git commit -m "Add Dockerfile and Jenkinsfile for container build"
```
### Jenkins Job Configuration

With the repository, Dockerfile, and Jenkinsfile committed to GitHub, we need to create a Jenkins Pipeline job to execute the CI workflow. Instead of defining build logic directly inside the Jenkins UI, the pipeline is defined as code (`Jenkinsfile`) and stored alongside the application source. The approach ensures the CI process is versioned, reviewable, and reproducible.

Now we need to create the Jenkins job.
Let's navigate back to the Jenkins web UI and login as our admin user. The follow these steps:
- *New item* -> *`legacy-service-ci`* -> *Pipeline*
- *Definition:* Pipeline script from SCM
- *SCM:* Git
- *Repo URL:* git@github.com:<your_user>:\<repo>.git
- *Branch:* \*/main
- *Script path:* Jenkinsfile
- *Save*

In Jenkins, Freestyle Jobs define steps in the UI, while Pipeline Jobs define steps as code.
We chose:
```python
New Item → Pipeline
Definition: Pipeline script from SCM
```
This means:
- Jenkins pulls the `Jenkinsfile` from Git.
- The pipeline definition lives with the code.
- CI changes require Git commits (not UI edits).
- Build logic is reproducible across Jenkins instances.

With our SCM configuration using Git, this is what happens during execution; When a build starts:
1. Jenkins authenticates via SSH (using the deploy key).
2. The repository is cloned into a workspace directory: `/var/lib/jenkins/workspace/<job-name>/`.
3. The `Jenkinsfile` is loaded.
4. Jenkins interprets the pipeline stages.
5. Each stage executes on the Jenkins node.

Each pipeline run:
- Creates or reuses a workspace directory.
- Cleans files depending on pipeline behavior.
- Stores build artifacts temporarily.
- Is isolated per job.
Build state does not persist across runs unless explicitly archived.

**First Test Build**  
Let's attempt our first build by selecting "Build Now".

We'll notice that the Jenkins build failed and if we look at the console output, we may encounter a 403 Forbidden error when trying to connect to the ECR.

When we try and run the following commands:
```bash
aws sts get-caller-identity
aws ecr describe-repositories --region us-east-1 --repository-names springboot-legacy-app
```

We notice the first one returns successfully, but the second doesn't. That confirms that our Jenkins IAM user `jenkins-ecr` is missing ECR permissions. It's authenticated (via first command's success), but not authorized for `ecr:DescribeRepositories` (and very likely also missing the push actions), which is why we hit the 403 during the push phase of the Jenkins build.

To fix this, we can attach an inline IAM policy to the `jenkins-ecr` user that allows:
- `ecr:GetAuthorizationToken` (needed for `docker login`)
- repo-scoped push/pull actions (needed for `docker push`)
- optionally `ecr:DescribeRepositories` (nice for repo-existence checks)

Navigate to *IAM -> Users -> `jenkins-ecr` -> Add permissions -> Create inline policy -> JSON*, and add this into the Policy editor:
```JSON
{
	"Version": "2012-10-17",
	"Statement": [
		{
			"Sid": "EcrAuthToken",
			"Effect": "Allow",
			"Action": "ecr:GetAuthorizationToken",
			"Resource": "*"
		},
		{
			"Sid": "EcrPushPullToSpecificRepo",
			"Effect": "Allow",
			"Action": [
				"ecr:BatchCheckLayerAvailability",
				"ecr:BatchGetImage",
				"ecr:CompleteLayerUpload",
				"ecr:GetDownloadUrlForLayer",
				"ecr:InitiateLayerUpload",
				"ecr:PutImage",
				"ecr:UploadLayerPart",
				"ecr:DescribeRepositories",
				"ecr:DescribeImages",
				"ecr:ListImages"
			],
			"Resource": "arn:aws:ecr:us-east-1:<Account_ID>:repository/springboot-legacy-app"
		}
	]
}
```
Now we should have the correct permissions in place to support the entire build process. 

Let's retry the Jenkins build and verify in AWS:
When we navigate to ECR and click on our repo and then "Images," we should see tags for our container builds.

**Build Metadata**  
Each run generates:
- A unique `BUILD_NUMBER`
- A timestamp
- A build log
- A workspace snapshot
We used:
```ruby
IMAGE_TAG = "${env.BUILD_NUMBER}"
```
This ties:
- Docker image version
- Jenkins build record
- Git revision
Together into a traceable artifact.

### Key Problems Solved

**Issue:**  
During the implementation of a Jenkins CI pipeline for the legacy Spring Boot application, the Maven build stage consistently failed with the following error:
- `error: release version 17 not supported`
This error occurred despite Jenkins reporting Java 21 as the active runtime. The failure blocked downstream stages, such as the Docker build and ECR push, preventing the CI pipeline from completing.

**Diagnostic:**  
There were contradicting signals:
- `java --version` → OpenJDK 21 (good)
- `mvn --version` → Java 21 runtime (good)
- Maven compiler configured with `--release 17` (valid)
- Yet compilation failed with “release version 17 not supported”
This suggests it was a toolchain inconsistency rather than a code issue.

**Root Cause:**  
The Jenkins build environment had an a Java runtime available (JRE), but not a usable Java compiler (`javac`) compatible with Java 17.
- Jenkins had access to a JRE (or incomplete JDK)
- `javac` was missing from the execution PATH
- `JAVA_HOME` was unset in the Jenkins runtime
- Maven was able to run, but could not compile
Because Maven relies on `javac` (not just `java`) during compilation, the absence of a proper compiler caused Maven to fail with a misleading error message.
	Instead of reporting `javac not found`, Maven outputted: `release version 17 not supported`

**Secondary Contributing Factor:** *Jenkins Shell Behavior*  
When we attempted to harden the build with strict shell options resulted in:
	`set: Illegal option -o pipefail`

This was caused because:
- Jenkins `sh` steps default to `/bin/sh`
- On Ubuntu, `/bin/sh` → `dash`
- `dash` does not support `pipefail`

This is what caused early script termination and prevented effective debugging. It took many recommits, pushes, and Jenkins build attempts.

**Resolution:**
*Step 1: Install a Full Java 17 JDK on Jenkins Node*
On the Jenkins VM, install JDK and verify:
```bash
sudo apt install -y openjdk-17-jdk
javac --version
```

*Step 2: Explicitly Control Java Toolchain in Pipeline*
Updated the `Jenkinsfile` to explicitly export the correct Java environment:
```Jenkinsfile
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
```
This ensured Maven consistently used a Java 17 compiler regardless of Jenkins defaults.

*Step 3: Force Bash for Build Steps*
To avoid `/bin/sh` limitations, Maven build steps were wrapped in Bash:
```groovy
bash -lc '
  set -e
  export JAVA_HOME=...
  mvn clean package
```
This eliminated shell incompatibility and improved error visibility.

**Preventative Measures:**
1. Never Assume Java Runtime == Java Compiler
	- Always verify `javac`, not just `java`
	- CI nodes frequently have runtimes but not full JDKs
2. Explicitly Control Build Toolchains
	- Set `JAVA_HOME` and `PATH` in CI jobs 
	- Avoid relying on system defaults
3. Understand Jenkins Shell Semantics
	- Jenkins uses `/bin/sh` by default
	- Bash features must be explicitly invoked
	- Each `sh` step is a separate shell
4. Debug Inside the CI Environment
	- Always validate tooling *inside Jenkins*, not from SSH sessions
	- CI environments often differ from interactive shells

**Summary:**
We encountered a Maven build failure in Jenkins due to a Java toolchain mismatch. Although Jenkins reported Java 21, the environment lacked a usable `javac` compiler. We resolved this by installing a full Java 17 JDK and explicitly controlling the build environment within the pipeline, ensuring deterministic builds and preventing future CI drift.

---
## Platform VM & EKS

### Amazon EKS Cluster Creation

With the CI pipeline producing versioned container images in Amazon ECR, the next step is provisioning a managed Kubernetes control plane using Amazon Elastic Kubernetes Service (EKS).

EKS provides:
- A managed Kubernetes control plane
- High availability across multiple Availability Zones
- Automatic patching and upgrades of the control plane
- Native integration with AWS networking and IAM

EKS was chosen over self-managed Kubernetes to eliminate control plane maintenance overhead and focus the lab on workload migration and CI automation rather than cluster lifecycle management.

Let's create our cluster by navigating to *EKS* in our AWS console. For our lab, let's select: *Quick configuration (EKS Auto Mode)*
We choose this option because our objective in this lab is validating:
- CI → ECR → EKS workflow
- Pod scheduling
- Health checks
- Network connectivity
- Deployment mechanics
We're not delving into custom VPC design, custom node groups, or deep cluster networking tuning. Using "Quick configuration" provides a managed control plane, managed node provisioning, sensible defaults, and mutli-AZ setup. This design choice accelerates platform validation without overengineering our infrastructure.

We can choose any Cluster Name we see fit, but this lab opted for the default auto-generated name `hilarious-dubstep-goose`. We want to have Kubernetes Version 1.34, which was the latest stable version available at the time of creation. This way, we:
- Avoid deprecated APIs
- Ensure compatibility
- Align with long-term support windows

For our Cluster IAM Roles, we're concerned with two:
- Cluster IAM Role: `AmazonEKSClusterRole`
- Node IAM Role `AmazonEKSAutoNodeRole`

Both these IAM roles are created by selecting "Create recommended role".

Selecting `AmazonEKSClusterRole` as our Cluster IAM role allows the control plane to:
- Manage networking resources
- Attach ENIs (Elastic Network Interfaces)
- Interact with AWS APIs
This role is assumed by the EKS service itself.

Our Node IAM role, `AmazonEKSAutoNodeRole`, is attached to EC2 worker nodes and allows them to:
- Register with the Cluster
- Pull container images from ECR
- Write logs to CloudWatch
- Communicate with AWS APIs
Without this role, nodes cannot join and pods cannot pull private images. This is vital for ECR-based deployments.

For our VPC, we'll be using the default VPC. This implies:
- Public subnets
- Internet Gateway attached
- Outbound NAT already configured

For our subnets, multiple public subnets across availability zones were selected. This is important because:
- Control plane ENIs are distributed across AZs
- Worker nodes are distributed for HA
- Pods can schedule across multiple AZs

In production, we would use private subnets and nodes would sit behind NAT, restricting our public exposure. For the sake of this lab, public subnets simplify connectivity and reduce networking friction.

When we click *Create*, AWS provisions:
1. Managed Kubernetes control plane
2. Elastic Network Interfaces (ENIs)
3. Security groups
4. Managed node infrastructure
5. Cluster endpoint
6. IAM integration
This typically takes around ten to fifteen minutes. We can validate our Cluster once the status becomes `Active`. From here on, we're prepared to generate `kubeconfig` on our Platform VM, which is discussed in the following subsection.

### Platform VM & kubectl Configuration

To mirror real-world platform operations, we'll provision a dedicated "control workstation" in our local Proxmox environment. This VM is not part of the application runtime or CI system. It exists solely to administer the Kubernetes cluster.

This separation reflects common production patterns where:
- CI systems build artifacts
- Clusters run workloads
- Operators manage infrastructure from a separate environment

Let's begin by creating the Platform VM. We'll use a new Ubuntu 22.04 VM with:
- 2 CPU cores
- 2-4 GB RAM
- 30-40 GB disk
The VM must have outbound internet access (to AWS endpoints) and the ability to reach the EKS API endpoint. No inbound public exposure is required.

After the Platform VM has undergone basic configuration, let's install the required tooling.

We need to install `kubectl`. It's easiest using `snap`:
```bash
sudo snap install kubectl --classic
kubectl version --client
```
`kubectl` minor version should match or be within one minor version of the Cluster version.

`kubectl` is a Kubernetes client. It does not run inside the Cluster and does not host workloads. It simply communicates with the Kubernetes API server. It can run anywhere that has network access to the Cluster endpoint, valid authentication credentials, and a properly `kubeconfig` file.

For EKS specifically, authentication is handled via AWS IAM and authorization is mapped to Kubernetes RBAC (Role-Based Access Control).

We need to additionally install `AWS CLI v2`, since EKS relies on AWS CLI to generate `kubeconfig`, inject an IAM-based authentication plugin, and retrieve temporary tokens.
```bash
# Download AWS CLI v2 installer
curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip"

# Extract installer
unzip -q awscliv2.zip

# Install AWS CLI v2
sudo ./aws/install

# Verify installation
aws --version
```

In the case that the Cluster was created in the AWS console under a different identity (i.e. root), let's create a new IAM user to administer the cluster from the platform VM.

Create `eks-admin` IAM user
- Attach policies directly
- Check *AdministratorAccess*
- Create user
- Create Access Keys (CLI use case) and store safely
In a production environment, this would be replaced with a scoped role and short-lived credentials. For lab validation, administrative privileges simplify access control.

We can now configure AWS credentials on the platform VM and verify identity:
```bash
aws configure
# Input all appropriate credential info for eks-admin IAM user
aws sts get-caller-identity
```
This confirms that the credentials are valid, AWS API calls succeed, and our IAM identity is active.

Let's proceed by generating our local cluster access configuration (generating `kubeconfig` for EKS):
```bash
aws eks update-kubeconfig --region us-east-1 --name hilarious-dubstep-goose
```
This command fetches our cluster endpoint, retrieves certificate authority data, writes configuration to `~/.kube/config` and injects an authentication exec plugin.

If we now try to validate our Cluster connectivity by running
```bash
kubectl get nodes
```

We'll notice it won't potentially work because we created the Cluster before `eks-admin`, meaning we created the Cluster with root.
- EKS gave cluster-admin to the identity that created the cluster, in this case root, and our new `eks-admin` user isn’t authorized yet, so `kubectl` gets “provide credentials”.
- On VM, get our `eks-admin` ARN with: `aws sts get-caller-identity`. Copy the ARN.
- Navigate to *AWS Console -> EKS -> Clusters -> `hilarious-dubstep-goose` -> Access -> Access entries -> Create access entry*
	- Principal: paste the `eks-admin` ARN
	- Type: Standard IAM access entry
	- Access policies: attach *AmazonEKSClusterAdminPolicy*
		- Scope: Cluster
- What this does:
	- Maps your IAM user (`eks-admin`) to Kubernetes RBAC cluster-admin without touching `aws-auth` manually.

Now if we update `kubeconfig` and retry validating our Cluster connectivity:
```bash
aws eks update-kubeconfig --region us-east-1 --name hilarious-dubstep-goose
kubectl get nodes
```
We should see our expected output as:
- Worker nodes listed
- STATUS: Ready
- Kubernetes version displayed

This confirms:
- IAM authentication works
- EKS RBAC mapping works
- Networking is functional
- `kubeconfig` is valid

**Deploy ECR Image into EKS**  
With EKS access configured and container images publishing successfully to ECR, the next step is deploying the application as a Kubernetes workload. 

This deployment is intentionally treated as an initial platform validation:
- Confirm EKS can pull the private image from ECR
- Confirm pods can schedule and start
- Confirm Kubernetes health checks behave as expected

At this stage, full application functionality may not be available yet if the PostgreSQL dependency is not reachable from within AWS (i.e. still hosted in our local lab network or not yet  migrated to RDS). A failing deployment due to database connectivity can be treated as an expected dependency gap rather than a Kubernetes platform failure.

1. Set Variables
Let's begin by defining deployment variables. These variables are helpful because we avoid copy/paste mistakes, keep manifests reusable, and make it easy to swap image versions:
```bash
export NS=legacy
export APP=legacy-app
export IMAGE="<ECR_IMAGE_URI_WITH_TAG>"
# Verification
echo "$NS $APP $IMAGE"
```

2. Create Kubernetes Namespace and Service Account
Creating the Kubernetes Namespace isolates resources for this workload. Creating the Service Account establishes a placeholder identity for future enhancements (e.g., IRSA - AWS security feature for EKS allowing you to associate a specific AWS IAM role with a K8S service account). Full disclosure, the `serviceaccount` isn't strictly required yet, but keeping it now makes later AWS integration cleaner.
```bash
kubectl create namespace $NS --dry-run=client -o yaml | kubectl apply -f -
kubectl -n $NS create serviceaccount ${APP}-sa --dry-run=client -o yaml | kubectl apply -f -
```

3. Create Deployment and Service Manifest
A *Deployment* manages the desired state of the application pods, including:
- Replica count
- Rolling updates
- Self-healing (restarts/replacement)

Creating a *Service* provides a stable in-cluster endpoint (DNS and virtual IP) for the pods, allowing consumers to reach the application without tracking pod IPs.

Let's create `legacy-app.yaml`:
```YAML
apiVersion: apps/v1
kind: Deployment
metadata:
  name: legacy-app
  namespace: legacy
spec:
  replicas: 2
  selector:
    matchLabels:
      app: legacy-app
  template:
    metadata:
      labels:
        app: legacy-app
    spec:
      serviceAccountName: legacy-app-sa
      containers:
        - name: legacy-app
          image: <Account-ID>.dkr.ecr.us-east-1.amazonaws.com/springboot-legacy-app:<Image-Tag>
          imagePullPolicy: IfNotPresent
          ports:
            - containerPort: 8080
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8080
            initialDelaySeconds: 10
            periodSeconds: 10
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8080
            initialDelaySeconds: 30
            periodSeconds: 20
---
apiVersion: v1
kind: Service
metadata:
  name: legacy-app-svc
  namespace: legacy
spec:
  selector:
    app: legacy-app
  ports:
    - name: http
      port: 80
      targetPort: 8080
  type: ClusterIP
```

Let's apply the Manifest:
```bash
kubectl apply -f legacy-app.yaml
```
The expected output should be:
```bash
deployment.apps/legacy-app created
service/legacy-app-svc created
```
We can now observe the pod lifecycle by running:
```bash
kubectl -n "$NS" get pods -w
```
We want to see:
- `ContainerCreating` as `Running`
- `Ready` becoming `1/1` for each pod
- No repeated restarts
If the database is not reachable and the application requires PostgreSQL at startup, we may encounter:
- `CrashLoopBackOff`
- readiness probe failing
- errors in logs about datasource connection failure
This is acceptable at this stage because the database boundary has not yet been migrated into AWS networking (RDS) or otherwise reachable from EKS. If we wish to make the app start without the database, to validate HTTP functionality first, we could configure the app not to hard-fail when the database is down. This isn't necessary unless you want the pods to go `Ready` now.

At this point, the CI system is producing deployable container images and EKS is capable of scheduling the workload. Any remaining failures are likely related to our PostgreSQL external dependency reachability, which will be resolved during the RDS migration and networking configuration phase.

4. Quick Troubleshooting
```bash
# Check events (image pulls, probes, scheduling)
kubectl -n "$NS" describe pod -l app=legacy-app

# Check logs
kubectl -n "$NS" logs -l app=legacy-app --tail=100
```
Common outcomes and meaning:
- `ImagePullBackOff`: ECR auth/role/network issue
- `CrashLoopBackOff`: Application runtime error (often database connection)
- `Readiness probe failed`: Application running but not "ready" (dependency is not healthy)

---
# External Dependency Integration (PostgreSQL / RDS)

**Status:** *In Progress*

---
# Teardown & Cost Cleanup

After we complete our AWS EKS lab, want to ensure we don't incur any additional charges, and are ready to bring the effort to an end, we can take the following steps. We want to ensure the following:
- All compute resources are terminated
- No hidden infrastructure remains
- Billing stops immediately
- No orphaned regional resources
- No lingering *CloudWatch* or *NAT* charges

For the purpose of birds-eye view clarity and environmental clarity, this is our typical stack:
- Amazon EKS (control plane)
- EC2 Worker Nodes (Auto Mode / Node Groups)
- NAT Gateway
- Application Load Balancer
- ECR Repository
- CloudWatch logs
- EBS volumes

## Delete EKS Control Plane
This will most likely be our primary cost driver. 
**Console:**  
```Code
EKS -> Clusters -> Select Cluster -> Delete
```
**CLI:**  
```
aws eks delete-cluster --name <cluster-name>
```
This will stop our EKS control plane billing (~$70/month)

## Verify EC2 Worker Nodes Are Terminated
After our cluster deletion, navigate to:
```code
EC2 -> Instances
```
Ensure to terminate any remaining instances. Do not leave them as stopped. Ensure no Auto Scaling Groups remain.
**CLI:**  
```bash
aws ec2 describe-instances
```

## Remove Hidden Cost Drivers
1. **NAT Gateway**
   - `VPC -> NAT Gateways -> Delete`
   - NAT Gateway can cost potentially $30-$35, even if idle

2. **Load Balancers**  
   - `EC2 -> Load Balancers -> Delete`
   - Also delete unused Target Groups

3. **EBS Volumes**  
   - `EC2 -> Volumes`
   - Delete unattached volumes and orphaned storage from terminated instances

4. **Elastic IPs**  
   - `EC2 -> Elastic IPs -> Release`
   - Unattached elastic IPs incur hourly charges

## Registry & Storage Cleanup
**Delete ECR Repositories:**  
`ECR -> Repositories -> Delete`

**Delete S3 Buckets (if used):**  
`S3 -> Bucket -> Empty -> Delete`

## CloudWatch Cleanup
Deleting EKS does not delete log groups.

**Delete Log Groups:**  
`CloudWatch -> Logs -> Log groups`

Delete:
- `/aws/eks/cluster/<cluster-name>/cluster`
- `/aws/containerinsights/*`
- Application log groups
- VPC flow logs
CloudWatch charges for log ingestion, log storage, and metrics.

**Delete Alarms:**  
`CloudWatch -> Alarms -> Delete`

## Regional Overview & Billing Verification
Switch AWS region dropdown and verify no resources exist in other neighboring regions. Resources are region-specific, but billing is not.

For verifying our billing, navigate to:
`Billing -> Bills`
Confirm that estimated charges stops increasing and no active service charges remain. Give it a couple hours wait time and refresh.

**Preventative Guardrail: Budget Alert**  
`Billing -> Budgets -> Create budget -> $X.XX threshold`

**Lessons Learned:**  
- EKS control plane incurs cost even without workloads.
- NAT Gateways are silent cost drivers.
- CloudWatch logs persist even after infrastructure deletion.
- Regional sweep is very important.
- Budget alerts should be configured before lab deployment.

Cloud engineering is not just about deployment; It's also about controlled decommissioning.

---

# Future Upgrades
- **Web Hook Trigger**
	- Automatically triggers Jenkins pipeline
- **Continuous Deployment: ArgoCD**

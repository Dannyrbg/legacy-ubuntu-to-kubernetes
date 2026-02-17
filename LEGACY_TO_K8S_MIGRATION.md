
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

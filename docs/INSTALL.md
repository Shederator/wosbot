This file is a guideline to build and launch the bot from the source code.

# pre-requesite 

- basic understanding for git commands.
- basic understanding for terminal commands.


# Install emulator


Supported : mumuplayer, ldplayer, memu
Recommended : [mumuplayer](https://www.mumuplayer.com/)

Setup emulator : 
- mandatory : screen : 720x1280, 320 DPI
- recommended performance : medium perf 4 cores, 2Go, 
- optional : 30 fps.

note : the first mumu emulator instance cannot be deleted. Use it.

check : lanch emulator it should run ok.

# Install wos

Launch emulator.  
Connect google play to your account and install whiteout survival.  
Launch wos.  
Go to game settings :
- **Mandatory** : disable night/day effect, disable snow effet.
- **Mandatory** : set language to english.
- optional : 30 fps, graphics normal.

check : you shall be able to play with your account.

# Build bot

## pre-requesites 
	
Tools and toolchains installed. Execute those commands in a powershell terminal.  

- winget install Microsoft.Git
- winget install EclipseAdoptium.Temurin.25.JDK 
- [download](https://maven.apache.org/download.cgi) and install apache maven in  : C:\
- verify your system path, open a powershell as admin and execute : 
	```
		[Environment]::GetEnvironmentVariable("PATH","Machine") -split ";"
	```
	path to the installed JDK should be here, maven needs to be added. Call those command to update path (remove jdk path if already there, adapt maven version)
	```
		$old = [Environment]::GetEnvironmentVariable("PATH","Machine")
		$new = $old + ";C:\Program Files\Eclipse Adoptium\jdk-25.0.2.10-hotspot\bin;C:\apache-maven-3.9.13\bin"
		[Environment]::SetEnvironmentVariable("PATH", $new, "Machine")
	```		
	
check : 
relaunch a powershell terminal and call : 
```
	java -version (display version)
	mvn -version (display version)
```	

- optional : 
	- winget DBBrowserForSQLite.DBBrowserForSQLite (browse your database)
	- winget Microsoft.VisualStudioCode (Edit the code)

## Get the source : 

Open a git terminal and clone project using the github link
```
	git clone https://github.com/<user>/wosbot/tree/main
```	
	
## Build

Open a cmd terminal and go to the source root folder and call : mvn clean install package
	```
		mvn clean install package
	```	

Check : 
- logs of build printing at the end a "build success" message, a wos-hmi folder is createdd
- with a cmd terminal go to the 'wosbot\wos-hmi\target' fodler and startup the bot with this command : 
	```java -jar wos-bot-1.7.0.jar```		
	
Note : you may need to adapt the filename to build version.

## Configure the bot

At the very minimum you should configure the player in the config section before starting the bot : 
- Mumu : C:\Program Files\Netease\MuMuPlayer\nx_main\MuMuManager.exe
- Memu : todo
- LDplayer : todo

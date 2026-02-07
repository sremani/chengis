pipeline {
    agent any
    stages {
        stage('Work') {
            steps {
                sh 'echo hello && sleep 0.1'
            }
        }
    }
}

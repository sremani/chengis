pipeline {
    agent any
    stages {
        stage('Run') {
            steps {
                sh 'echo hello'
            }
        }
    }
}

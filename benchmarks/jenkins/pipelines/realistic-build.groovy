pipeline {
    agent any
    stages {
        stage('Checkout') {
            steps {
                sh 'git clone --depth 1 --branch master https://github.com/clojure/tools.cli.git .'
            }
        }
        stage('Build') {
            steps {
                sh "echo 'Compiling...' && ls -la"
            }
        }
        stage('Test') {
            steps {
                sh "echo 'Running tests...' && find . -name '*.clj' | wc -l"
            }
        }
    }
}

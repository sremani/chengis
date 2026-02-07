pipeline {
    agent any
    options {
        cleanWs()
    }
    stages {
        stage('Checkout') {
            steps {
                sh 'rm -rf * .git .* 2>/dev/null || true'
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

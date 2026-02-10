(ns chengis.web.views.notifications
  "Browser notification components for build completion alerts.
   Uses HTML5 Notification API triggered by SSE build-completed events.
   Feature-flag gated via :browser-notifications."
  (:require [hiccup.util :refer [raw-string]]))

(defn notification-script
  "Inline script for HTML5 browser notifications on build completion.
   Connects to the global SSE endpoint and shows a browser notification
   when a build completes. Narrow exception to zero-JS rule (browser-native
   API with no HTML fallback)."
  []
  [:script (raw-string
    "
    (function() {
      var notificationsEnabled = localStorage.getItem('chengis-notifications') === 'true';
      var toggle = document.getElementById('notification-toggle');
      if (toggle) {
        toggle.textContent = notificationsEnabled ? '🔔' : '🔕';
        toggle.title = notificationsEnabled ? 'Notifications enabled' : 'Notifications disabled';
      }
      if (notificationsEnabled && 'Notification' in window && Notification.permission === 'granted') {
        var evtSource = new EventSource('/api/events/global');
        evtSource.addEventListener('build-completed', function(e) {
          try {
            var data = JSON.parse(e.data);
            new Notification('Chengis CI', {
              body: data.operation + ': ' + data.status,
              icon: '/favicon.ico',
              tag: 'build-' + data.buildId
            });
          } catch(err) {}
        });
      }
    })();
    ")])

(defn notification-toggle
  "Nav bar toggle button for enabling/disabling browser notifications.
   Requests permission on first enable."
  []
  [:button {:id "notification-toggle"
            :class "text-gray-400 hover:text-yellow-300 transition text-sm cursor-pointer"
            :title "Toggle notifications"
            :onclick (str
              "var enabled = localStorage.getItem('chengis-notifications') === 'true';"
              "if (!enabled) {"
              "  if ('Notification' in window) {"
              "    Notification.requestPermission().then(function(p) {"
              "      if (p === 'granted') {"
              "        localStorage.setItem('chengis-notifications', 'true');"
              "        this.textContent = '🔔';"
              "        this.title = 'Notifications enabled';"
              "        location.reload();"
              "      }"
              "    }.bind(this));"
              "  }"
              "} else {"
              "  localStorage.setItem('chengis-notifications', 'false');"
              "  this.textContent = '🔕';"
              "  this.title = 'Notifications disabled';"
              "  location.reload();"
              "}")}
   "🔕"])

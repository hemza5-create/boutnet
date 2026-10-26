# boutnet
<div class="main-container">
    <!-- شريط أدوات الكاميرا ومشاركة الشاشة -->
    <div class="media-toolbar">
        <button class="btn" onclick="toggleCamera()">📷 الكاميرا (أمامية/خلفية)</button>
        <button class="btn" onclick="startScreenShare()">🖥️ مشاركة الشاشة</button>
    </div>

    <div class="media-preview-container" id="mediaPreview">
        <video id="videoElement" autoplay playsinline></video>
    </div>

    <div class="chat-box" id="chatBox">
        <div class="message bot">مرحباً بك في بوتنيت (Boutnet)! أنا جاهز لمساعدتك، متصل بكاميرتك وشاشتك، تفضل بالسؤال أو اطلب ما ترغب به.</div>
    </div>

    <div class="input-area">
        <input type="text" id="userInput" placeholder="اكتب رسالتك هنا..." onkeypress="handleKeyPress(event)">
        <button class="btn" onclick="sendMessage()">إرسال</button>
    </div>
</div>

<script>
    let currentFacingMode = 'user';
    let mediaStream = null;

    async function toggleCamera() {
        const videoElement = document.getElementById('videoElement');
        const previewContainer = document.getElementById('mediaPreview');
        
        try {
            if (mediaStream) {
                mediaStream.getTracks().forEach(track => track.stop());
            }
            currentFacingMode = currentFacingMode === 'user' ? 'environment' : 'user';
            mediaStream = await navigator.mediaDevices.getUserMedia({ 
                video: { facingMode: currentFacingMode }, 
                audio: false 
            });
            videoElement.srcObject = mediaStream;
            previewContainer.style.display = 'block';
        } catch (err) {
            alert('تعذر فتح الكاميرا، تأكد من منح الأذونات اللازمة.');
        }
    }

    async function startScreenShare() {
        const videoElement = document.getElementById('videoElement');
        const previewContainer = document.getElementById('mediaPreview');
        
        try {
            if (mediaStream) {
                mediaStream.getTracks().forEach(track => track.stop());
            }
            mediaStream = await navigator.mediaDevices.getDisplayMedia({ video: true });
            videoElement.srcObject = mediaStream;
            previewContainer.style.display = 'block';
        } catch (err) {
            alert('تم إلغاء مشاركة الشاشة أو لم يتم السماح بها.');
        }
    }

    function sendMessage() {
        const input = document.getElementById('userInput');
        const chatBox = document.getElementById('chatBox');
        if (!input.value.trim()) return;

        const userMsg = document.createElement('div');
        userMsg.className = 'message user';
        userMsg.textContent = input.value;
        chatBox.appendChild(userMsg);

        const text = input.value;
        input.value = '';
        chatBox.scrollTop = chatBox.scrollHeight;

        setTimeout(() => {
            const botMsg = document.createElement('div');
            botMsg.className = 'message bot';
            botMsg.textContent = "لقد تلقيت طلبك بخصوص: " + text + ". أنا جاهز لتنفيذه عبر الكاميرا أو الشاشة.";
            chatBox.appendChild(botMsg);
            chatBox.scrollTop = chatBox.scrollHeight;
        }, 1000);
    }

    function handleKeyPress(e) {
        if (e.key === 'Enter') sendMessage();
    }
</script>
